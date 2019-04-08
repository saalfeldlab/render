package org.janelia.render.client.betterbox;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import mpicbg.trakem2.transform.AffineModel2D;

import org.janelia.alignment.ArgbRenderer;
import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.betterbox.BoxData;
import org.janelia.alignment.betterbox.RenderedBox;
import org.janelia.alignment.betterbox.RenderedBoxParent;
import org.janelia.alignment.mipmap.BoxMipmapGenerator;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.FileUtil;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.alignment.util.LabelImageProcessorCache;
import org.janelia.alignment.util.ProcessTimer;
import org.janelia.alignment.util.RenderWebServiceUrls;
import org.janelia.render.client.parameter.MaterializedBoxParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for rendering uniform (but arbitrarily sized) boxes (derived tiles) to disk.
 *
 * All generated images have the same dimensions and pixel count and are stored within a
 * CATMAID LargeDataTileSource directory structure that looks like this:
 * <pre>
 *         [root directory]/[tile width]x[tile height]/[level]/[z]/[row]/[col].[format]
 * </pre>
 *
 * Details about the CATMAID LargeDataTileSource can be found at
 * <a href="https://github.com/catmaid/CATMAID/blob/master/django/applications/catmaid/static/js/tile-source.js">
 *     https://github.com/catmaid/CATMAID/blob/master/django/applications/catmaid/static/js/tile-source.js
 * </a>).
 *
 * @author Eric Trautman
 */
public class BoxGenerator
        implements Serializable {

    private final RenderWebServiceUrls webServiceUrls;
    private final MaterializedBoxParameters boxParameters;
    private final Bounds stackBounds;

    private final String stack;
    private final String format;
    private final int boxWidth;
    private final int boxHeight;
    private final String baseBoxPath;
    private final String boxPathSuffix;
    private final File emptyImageFile;

    /**
     * Constructs a generator with the specified parameters.
     *
     * @param  renderWebParameters  web service parameters for retrieving render stack data.
     * @param  boxParameters        parameters specifying box properties.
     * @param  stackBounds          bounds for the stack being rendered.
     */
    public BoxGenerator(final RenderWebServiceParameters renderWebParameters,
                        final MaterializedBoxParameters boxParameters,
                        final Bounds stackBounds) {

        this.webServiceUrls = new RenderWebServiceUrls(renderWebParameters.baseDataUrl,
                                                       renderWebParameters.owner,
                                                       renderWebParameters.project);
        this.boxParameters = boxParameters;
        this.stackBounds = stackBounds;

        this.stack = boxParameters.stack;
        this.format = boxParameters.format;
        this.boxWidth = boxParameters.width;
        this.boxHeight = boxParameters.height;

        String boxName = this.boxWidth + "x" + this.boxHeight;
        if (boxParameters.label) {
            boxName += "-label";
        }

        final Path boxPath = Paths.get(boxParameters.rootDirectory,
                                       renderWebParameters.project,
                                       boxParameters.stack,
                                       boxName).toAbsolutePath();

        final File boxDirectory = boxPath.toFile().getAbsoluteFile();
        this.baseBoxPath = boxDirectory.getPath();
        this.boxPathSuffix = "." + format.toLowerCase();

        this.emptyImageFile = new File(boxDirectory.getAbsolutePath(),
                                       "empty." + format.toLowerCase());
    }

    /**
     * @return absolute path of the base parent directory for all rendered boxes
     *         (e.g. '/nrs/spc/rendered_boxes/spc/aibs_mm2_data/1024x1024').
     */
    public String getBaseBoxPath() {
        return baseBoxPath;
    }

    /**
     * @return suffix for all rendered boxes (e.g. '.jpg').
     */
    public String getBoxPathSuffix() {
        return boxPathSuffix;
    }

    /**
     * @return the common/shared empty image file (with same dimensions as rendered box images).
     */
    public File getEmptyImageFile() {
        return emptyImageFile;
    }

    /**
     * Creates mipmap level subdirectories for all boxes along with an empty box file that can be shared.
     *
     * @throws IOException
     *   if any of the directories or files cannot be created.
     */
    public void setupCommonDirectoriesAndFiles()
            throws IOException {

        final File boxDirectory = new File(baseBoxPath);

        for (int level = 0; level <= boxParameters.maxLevel; level++) {
            final File levelDirectory = new File(boxDirectory, String.valueOf(level));
            FileUtil.ensureWritableDirectory(levelDirectory);
        }

        if (boxParameters.isOverviewNeeded()) {
            final File overviewDirectory = new File(boxDirectory, "small");
            FileUtil.ensureWritableDirectory(overviewDirectory);
        }

        if (boxParameters.createIGrid) {

            if (emptyImageFile.exists()) {

                LOG.info("skipping creation of {} because it already exists", emptyImageFile.getAbsolutePath());

            } else {

                if (boxParameters.label) {

                    final BufferedImage emptyImage = LabelImageProcessorCache.createEmptyImage(boxWidth, boxHeight);
                    Utils.saveImage(emptyImage, emptyImageFile.getAbsolutePath(), format, false, 0.85f);

                } else {

                    final BufferedImage emptyImage = new BufferedImage(boxWidth, boxHeight, BufferedImage.TYPE_INT_ARGB);
                    Utils.saveImage(emptyImage, emptyImageFile.getAbsolutePath(), format, true, 0.85f);

                }
            }
        }

    }

    /**
     * Renders the specified boxes.
     *
     * If the next level of mipmaps is to be rendered, parent boxes for complete 'families'
     * (where all siblings are specified) will also be rendered.
     *
     * @param  z                    z value for all boxes.
     * @param  level                mipmap level for all boxes.
     * @param  boxList              list of boxes to be rendered for a level.
     * @param  imageProcessorCache  cache for source image pixels.
     * @param  skipRendering        if true, nothing will be rendered (useful for explain plan use case).
     *
     * @return list of all boxes rendered (including parents).
     *
     * @throws IOException
     *   if any failures occur during rendering.
     */
    public List<BoxData> renderBoxesForLevel(final double z,
                                             final int level,
                                             final List<BoxData> boxList,
                                             final ImageProcessorCache imageProcessorCache,
                                             final boolean skipRendering)
            throws IOException {

        final Progress progress = new Progress(z, level, boxList.size(), 300);
        final List<BoxData> renderedBoxList = new ArrayList<>(boxList.size());

        int renderedLevelBoxCount = 0;
        RenderedBoxParent cachedParent = null;
        BoxData siblingParentBox = null;
        boolean renderCachedParent = false;
        BoxData parentBox;
        for (final BoxData boxData : boxList) {

            if (boxData.getLevel() < boxParameters.maxLevel) {

                parentBox = boxData.getParentBoxData();

                if (! parentBox.equals(siblingParentBox)) {
                    // Create a 'cache' of rendered child image pixels if there is a chance
                    // we are about to render all siblings in a parent box.
                    cachedParent = new RenderedBoxParent(parentBox,
                                                         baseBoxPath,
                                                         boxPathSuffix);
                    siblingParentBox = parentBox;
                }

                siblingParentBox.addChild(boxData);

                // If this is the last sibling, render the cached parent when done with this box.
                // Rendering the parent now should save up to three additional reads during the render
                // of the next level since each child has to be re-read to create the parent
                // (but if the parent already exists it still gets read for the next level).
                renderCachedParent = (siblingParentBox.getChildCount() == boxData.getNumberOfSiblings());
            }

            if (! skipRendering) {
                renderBox(boxData, imageProcessorCache, cachedParent);
                renderedLevelBoxCount++;
            }
            renderedBoxList.add(boxData);

            if (renderCachedParent) {

                if (! skipRendering) {
                    final BufferedImage parentImage = cachedParent.buildImage(boxWidth, boxHeight);
                    BoxMipmapGenerator.saveImage(parentImage,
                                                 cachedParent.getBoxFile(),
                                                 boxParameters.label,
                                                 format);
                }

                renderedBoxList.add(siblingParentBox);
            }

            progress.markProcessedBox(renderedLevelBoxCount, imageProcessorCache);
        }

        return renderedBoxList;
    }

    /**
     * Renders a CATMAID overview image for the specified layer.
     *
     * @param  z  z value for layer.
     *
     * @throws IOException
     *   if the overview image cannot be saved to disk.
     */
    public void renderOverview(final int z)
            throws IOException {

        LOG.info("renderOverview: entry, z={}", z);

        final int stackMaxX = stackBounds.getMaxX().intValue();
        final int stackMaxY = stackBounds.getMaxY().intValue();

        // find smallest box level that contains stack bounds in one box (or maxLevel)
        int sourceBoxLevel = 0;
        double levelScale = 1.0;
        double scaledStackMaxX = stackMaxX;
        double scaledStackMaxY = stackMaxY;

        for (; sourceBoxLevel <= boxParameters.maxLevel; sourceBoxLevel++) {
            levelScale = 1.0 / Math.pow(2, sourceBoxLevel);
            scaledStackMaxX = levelScale * stackMaxX;
            scaledStackMaxY = levelScale * stackMaxY;
            if ( ( (scaledStackMaxX < boxWidth) && (scaledStackMaxY < boxHeight) ) ||
                 (sourceBoxLevel == boxParameters.maxLevel)) {
                break;
            }
        }

        // find relative scale needed to make overview fit requested size
        final double overviewScale;
        if (stackMaxX > stackMaxY) {
            overviewScale = (double) boxParameters.maxOverviewWidthAndHeight / scaledStackMaxX;
        } else {
            overviewScale = (double) boxParameters.maxOverviewWidthAndHeight / scaledStackMaxY;
        }

        // build tile specs that reference box images rendered at the "best available" source level
        final RenderParameters overviewParameters =
                new RenderParameters(null,
                                     0, 0,
                                     (int) Math.ceil(scaledStackMaxX), (int) Math.ceil(scaledStackMaxY),
                                     overviewScale);

        final int numberOfRows = (int) (scaledStackMaxY / boxParameters.height) + 1;
        final int numberOfColumns = (int) (scaledStackMaxX / boxParameters.width) + 1;

        BoxData boxData;
        File boxFile;
        ImageAndMask imageAndMask;
        ChannelSpec channelSpec;
        TileSpec tileSpec;
        int x;
        int y;
        for (int row = 0; row < numberOfRows; row++) {
            for (int column = 0; column < numberOfColumns; column++) {
                boxData = new BoxData(z, sourceBoxLevel, row, column);
                boxFile = boxData.getAbsoluteLevelFile(baseBoxPath, boxPathSuffix);
                if (boxFile.exists()) {
                    imageAndMask = new ImageAndMask(boxFile, null);
                    if (boxParameters.label) {
                        channelSpec = new ChannelSpec(0.0, 65535.0); // use 16-bit intensity range for labels
                    } else {
                        channelSpec = new ChannelSpec();
                    }
                    channelSpec.putMipmap(0, imageAndMask);
                    tileSpec = new TileSpec();
                    tileSpec.addChannel(channelSpec);
                    x = column * boxParameters.width;
                    y = row * boxParameters.height;
                    tileSpec.addTransformSpecs(
                            Collections.singletonList(
                                    new LeafTransformSpec(AffineModel2D.class.getName(), "1 0 0 1 " + x + " " + y)));
                    tileSpec.setWidth((double) boxParameters.width);
                    tileSpec.setHeight((double) boxParameters.height);
                    tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true);
                    overviewParameters.addTileSpec(tileSpec);
                }
            }
        }

        // render the tile specs
        final int numberOfBoxes = overviewParameters.numberOfTileSpecs();

        LOG.info("renderOverview: z={}, sourceBoxLevel={}, levelScale={}, numberOfRows={}, numberOfColumns={}, numberOfBoxes={}, overviewScale={}",
                 z, sourceBoxLevel, levelScale, numberOfRows, numberOfColumns, numberOfBoxes, overviewScale);

        if (numberOfBoxes < 1000) {

            final BufferedImage overviewImage = overviewParameters.openTargetImage();
            ArgbRenderer.render(overviewParameters, overviewImage, ImageProcessorCache.DISABLED_CACHE);
            final File overviewFile = Paths.get(baseBoxPath, "small", z + boxPathSuffix).toFile();

            BoxMipmapGenerator.saveImage(overviewImage,
                                         overviewFile,
                                         boxParameters.label,
                                         format);
        } else {

            LOG.warn("renderOverview: skipping render for layer " + z + " because overview contains too many (" +
                     numberOfBoxes + ") boxes, " +
                     "specify a higher maxLevel to reduce the number of boxes needed for overview images");

        }

        LOG.info("renderOverview: exit, z={}", z);
    }

    private void renderBox(final BoxData boxData,
                           final ImageProcessorCache imageProcessorCache,
                           final RenderedBoxParent cachedParent)
            throws IOException {

        final File boxFile = boxData.getAbsoluteLevelFile(baseBoxPath, boxPathSuffix);

        if (boxParameters.forceGeneration || (! boxFile.exists())) {

            BufferedImage boxImage = null;

            if (boxData.getLevel() == 0) {

                String boxParametersUrl = webServiceUrls.getStackUrlString(stack) +
                                          boxData.getServicePath(boxWidth, boxHeight) +
                                          "/render-parameters";
                boxParametersUrl = RenderWebServiceUrls.addParameter("filterListName",
                                                                     boxParameters.filterListName,
                                                                     boxParametersUrl);

                LOG.info("renderBox: loading {}", boxParametersUrl);

                final RenderParameters renderParameters = RenderParameters.loadFromUrl(boxParametersUrl);
                renderParameters.setSkipInterpolation(boxParameters.skipInterpolation);
                renderParameters.setBinaryMask(boxParameters.binaryMask);

                if (boxParameters.label) {
                    // override intensity range for 16-bit labels
                    renderParameters.setMinIntensity(0.0);
                    renderParameters.setMaxIntensity(65535.0);

                    // make sure labels always use binary mask
                    renderParameters.setBinaryMask(true);
                }

                if (renderParameters.hasTileSpecs()) {

                    if (boxParameters.sortByClusterGroupId) {
                        renderParameters.sortTileSpecs(MaterializedBoxParameters.CLUSTER_GROUP_ID_COMPARATOR);
                    }

                    boxImage = BoxMipmapGenerator.renderBoxImage(renderParameters,
                                                                 imageProcessorCache);
                } else {
                    LOG.warn("renderBox: box {} is empty (no tile specs)", boxData);
                }

            } else {

                final RenderedBoxParent renderedBoxParent = new RenderedBoxParent(boxData,
                                                                                  baseBoxPath,
                                                                                  boxPathSuffix);
                renderedBoxParent.loadChildren();
                if (renderedBoxParent.hasChildren()) {
                    boxImage = renderedBoxParent.buildImage(boxWidth, boxHeight);
                } else {
                    LOG.warn("renderBox: box {} is empty (no rendered children)", boxData);
                }

            }

            if (boxImage != null) {

                BoxMipmapGenerator.saveImage(boxImage,
                                             boxFile,
                                             boxParameters.label,
                                             format);

                if (cachedParent != null) {
                    final RenderedBox renderedChild = new RenderedBox(boxFile, boxImage);
                    cachedParent.setChild(renderedChild, boxData.getParentIndex());
                }
            }

        } else {

            LOG.info("renderBoxFile: {} already generated", boxFile.getAbsolutePath());

            if (cachedParent != null)  {
                final RenderedBox renderedChild = new RenderedBox(boxFile);
                cachedParent.setChild(renderedChild, boxData.getParentIndex());
            }

        }

    }

    /**
     * Utility to support logging of progress during long running layer render processes.
     */
    private class Progress {

        private final double z;
        private final int level;
        private final int numberOfLevelBoxes;
        final ProcessTimer processTimer;
        private final SimpleDateFormat sdf;

        Progress(final double z,
                 final int level,
                 final int numberOfLevelBoxes,
                 final long logIntervalSeconds) {

            this.z = z;
            this.level = level;
            this.numberOfLevelBoxes = numberOfLevelBoxes;
            this.processTimer = new ProcessTimer(logIntervalSeconds * 1000);
            this.sdf = new SimpleDateFormat("HH:mm:ss");
        }

        void markProcessedBox(final int renderedLevelBoxCount,
                              final ImageProcessorCache imageProcessorCache) {

            if (processTimer.hasIntervalPassed() || (renderedLevelBoxCount == numberOfLevelBoxes)) {

                final int percentComplete = (int) ((double) renderedLevelBoxCount * 100 / numberOfLevelBoxes);

                String etaString = "";
                if (percentComplete > 0) {
                    final double msPerBox = processTimer.getElapsedMilliseconds() / (double) renderedLevelBoxCount;
                    final long remainingMs = (long) (msPerBox * (numberOfLevelBoxes - renderedLevelBoxCount));
                    final Date eta = new Date((new Date().getTime()) + remainingMs);
                    etaString = ", ETA is " + sdf.format(eta);
                }

                String cacheStats = "cache not applicable";
                if (! imageProcessorCache.equals(ImageProcessorCache.DISABLED_CACHE)) {
                    cacheStats = String.valueOf(imageProcessorCache.getStats());
                }

                LOG.info("renderBoxesForLevel: {} of {} layer {} level {} boxes rendered ({}%){}, {}",
                         renderedLevelBoxCount, numberOfLevelBoxes, z, level,
                         percentComplete, etaString, cacheStats);
            }
        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(BoxGenerator.class);
}

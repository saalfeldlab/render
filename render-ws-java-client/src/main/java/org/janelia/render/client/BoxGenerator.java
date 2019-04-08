package org.janelia.render.client;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.janelia.alignment.ArgbRenderer;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.mipmap.BoxMipmapGenerator;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackStats;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.alignment.util.LabelImageProcessorCache;
import org.janelia.render.client.parameter.MaterializedBoxParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for rendering uniform (but arbitrarily sized) boxes (derived tiles) to disk for one or more layers.
 *
 * An {@link ArgbRenderer} instance is first used to produce the full scale (level 0) boxes.
 * {@link BoxMipmapGenerator} instances are then used to produce any requested down-sampled mipmaps.
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
public class BoxGenerator implements Serializable {

    private final RenderWebServiceParameters renderWebParameters;
    private final MaterializedBoxParameters boxParameters;

    private final String stack;
    private final String format;
    private final int boxWidth;
    private final int boxHeight;
    private final File boxDirectory;
    private final File emptyImageFile;
    private final Bounds stackBounds;

    private transient RenderDataClient rdc;

    public BoxGenerator(final RenderWebServiceParameters renderWebParameters,
                        final MaterializedBoxParameters boxParameters)
            throws IOException {

        this.renderWebParameters = renderWebParameters;
        this.boxParameters = boxParameters;

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

        this.boxDirectory = boxPath.toFile();

        final File stackDirectory = this.boxDirectory.getParentFile();

        if (! stackDirectory.exists()) {
            if (! stackDirectory.mkdirs()) {
                // check for existence again in case another parallel process already created the directory
                if (! stackDirectory.exists()) {
                    throw new IOException("failed to create " + stackDirectory.getAbsolutePath());
                }
            }
        }

        if (! stackDirectory.canWrite()) {
            throw new IllegalArgumentException("not allowed to write to stack directory " + stackDirectory);
        }

        this.emptyImageFile = new File(boxDirectory.getAbsolutePath(),
                                       "empty." + format.toLowerCase());

        if (boxParameters.renderGroup != null) {

            if (boxParameters.numberOfRenderGroups == null) {
                throw new IllegalArgumentException(
                        "numberOfRenderGroups must be specified when renderGroup is specified");
            }

            if (boxParameters.renderGroup < 1) {
                throw new IllegalArgumentException("renderGroup values start at 1");
            }

            if (boxParameters.renderGroup > boxParameters.numberOfRenderGroups) {
                throw new IllegalArgumentException(
                        "numberOfRenderGroups (" + boxParameters.numberOfRenderGroups +
                        ") must be greater than the renderGroup (" + boxParameters.renderGroup + ")");
            }

        } else if (boxParameters.numberOfRenderGroups != null) {
            throw new IllegalArgumentException(
                    "renderGroup (1-n) must be specified when numberOfRenderGroups are specified");
        }

        final StackMetaData stackMetaData = getRenderDataClient().getStackMetaData(this.stack);
        final StackStats stats = stackMetaData.getStats();
        if (stats == null) {
            throw new IllegalArgumentException("missing bounds for stack " + this.stack +
                                               ", completing the stack will derive and store bounds");
        }
        this.stackBounds = stats.getStackBounds();
    }

    public RenderDataClient getRenderDataClient() {
        if (rdc == null) {
            rdc = new RenderDataClient(renderWebParameters.baseDataUrl,
                                       renderWebParameters.owner,
                                       renderWebParameters.project);
        }
        return rdc;
    }

    public void createEmptyImageFile()
            throws IOException {

        if (emptyImageFile.exists()) {

            LOG.debug("skipping creation of {} because it already exists", emptyImageFile.getAbsolutePath());

        } else {

            if (boxParameters.label) {

                final BufferedImage emptyImage = LabelImageProcessorCache.createEmptyImage(boxWidth, boxHeight);
                if (emptyImageFile.exists()) {
                    LOG.debug("skipping save of {} because it already exists", emptyImageFile.getAbsolutePath());
                } else {
                    Utils.saveImage(emptyImage, emptyImageFile.getAbsolutePath(), format, false, 0.85f);
                }

            } else {

                final BufferedImage emptyImage = new BufferedImage(boxWidth, boxHeight, BufferedImage.TYPE_INT_ARGB);
                if (emptyImageFile.exists()) {
                    LOG.debug("skipping save of {} because it already exists", emptyImageFile.getAbsolutePath());
                } else {
                    Utils.saveImage(emptyImage, emptyImageFile.getAbsolutePath(), format, true, 0.85f);
                }

            }
        }

    }

    public void generateBoxesForZ(final Double z)
            throws Exception {

        LOG.info("generateBoxesForZ: {}, entry, boxDirectory={}", z, boxDirectory);

        final Bounds layerBounds = getRenderDataClient().getLayerBounds(stack, z);
        final SectionBoxBounds boxBounds = new SectionBoxBounds(z, boxWidth, boxHeight, layerBounds);

        if (boxParameters.renderGroup != null) {
            boxBounds.setRenderGroup(boxParameters.renderGroup, boxParameters.numberOfRenderGroups, boxParameters.maxLevel);
        }

        final int tileCount;
        final ImageProcessorCache imageProcessorCache;
        if (boxParameters.label) {

            // retrieve all tile specs for layer so that imageUrls can be consistently mapped to label colors
            // (this allows label runs to be resumed after failures)

            final ResolvedTileSpecCollection resolvedTiles = getRenderDataClient().getResolvedTiles(stack, z);
            tileCount = resolvedTiles.getTileCount();

            imageProcessorCache = new LabelImageProcessorCache(ImageProcessorCache.DEFAULT_MAX_CACHED_PIXELS,
                                                               true,
                                                               false,
                                                               resolvedTiles.getTileSpecs());

        } else {

            final List<TileBounds> tileBoundsList = getRenderDataClient().getTileBounds(stack, z);
            tileCount = tileBoundsList.size();
            imageProcessorCache = new ImageProcessorCache();

        }

        LOG.info("generateBoxesForZ: {}, layerBounds={}, boxBounds={}, tileCount={}",
                 z, layerBounds, boxBounds, tileCount);

        BoxMipmapGenerator boxMipmapGenerator = new BoxMipmapGenerator(z.intValue(),
                                                                       boxParameters.label,
                                                                       format,
                                                                       boxWidth,
                                                                       boxHeight,
                                                                       boxDirectory,
                                                                       0,
                                                                       boxBounds.getFirstRow(),
                                                                       boxBounds.getLastRow(),
                                                                       boxBounds.getFirstColumn(),
                                                                       boxBounds.getLastColumn(),
                                                                       boxParameters.forceGeneration);
        final IGridPaths iGridPaths;
        if (boxParameters.createIGrid) {
            iGridPaths = new IGridPaths(boxBounds.getNumberOfRows(), boxBounds.getNumberOfColumns());
        } else {
            iGridPaths = null;
        }

        generateLevelZero(z,
                          boxBounds,
                          tileCount,
                          imageProcessorCache,
                          boxMipmapGenerator,
                          iGridPaths);

        if (iGridPaths != null) {
            final Path iGridDirectory = Paths.get(boxDirectory.getAbsolutePath(), "0", "iGrid");
            iGridPaths.saveToFile(iGridDirectory.toFile(), z, emptyImageFile);
        }

        final Path overviewDirPath = Paths.get(boxDirectory.getAbsolutePath(), "small");
        final String overviewFileName = z.intValue() + "." + format.toLowerCase();
        final File overviewFile = new File(overviewDirPath.toFile(), overviewFileName).getAbsoluteFile();
        boolean isOverviewGenerated = (! boxParameters.forceGeneration) && overviewFile.exists();

        if (isOverviewGenerated) {
            LOG.info("generateBoxesForZ: {}, overview {} already generated", z, overviewFile.getAbsolutePath());
        }

        for (int level = 0; level < boxParameters.maxLevel; level++) {
            boxMipmapGenerator = boxMipmapGenerator.generateNextLevel();
            if (boxParameters.isOverviewNeeded() && (! isOverviewGenerated)) {
                isOverviewGenerated = boxMipmapGenerator.generateOverview(boxParameters.maxOverviewWidthAndHeight,
                                                                          stackBounds,
                                                                          overviewFile);
            }
        }

        LOG.info("generateBoxesForZ: {}, exit", z);
    }

    private void generateLevelZero(final Double z,
                                   final SectionBoxBounds boxBounds,
                                   final int tileCount,
                                   final ImageProcessorCache imageProcessorCache,
                                   final BoxMipmapGenerator boxMipmapGenerator,
                                   final IGridPaths iGridPaths)
            throws IOException {

        final Progress progress = new Progress(tileCount);

        AlreadyGeneratedState lastAlreadyGeneratedBox = null;

        RenderParameters renderParameters;
        File levelZeroFile;
        int row = boxBounds.getFirstRow();
        int column;
        for (int y = boxBounds.getFirstY(); y <= boxBounds.getLastY(); y += boxHeight) {

            column = boxBounds.getFirstColumn();

            for (int x = boxBounds.getFirstX(); x <= boxBounds.getLastX(); x += boxWidth) {

                if (boxBounds.isInRenderGroup(row, column)) {

                    levelZeroFile = BoxMipmapGenerator.getImageFile(format,
                                                                    boxDirectory,
                                                                    0,
                                                                    boxBounds.getZ(),
                                                                    row,
                                                                    column);

                    if (boxParameters.forceGeneration || (!levelZeroFile.exists())) {

                        renderParameters = generateLevelZeroBox(x, y, z,
                                                                imageProcessorCache,
                                                                boxMipmapGenerator,
                                                                iGridPaths,
                                                                levelZeroFile,
                                                                row,
                                                                column);
                    } else {

                        LOG.info("{} already generated", levelZeroFile.getAbsolutePath());

                        lastAlreadyGeneratedBox = new AlreadyGeneratedState(x, y, row, column);

                        renderParameters = null;

                        boxMipmapGenerator.addSource(row, column, levelZeroFile);

                        if (iGridPaths != null) {
                            iGridPaths.addImage(levelZeroFile, row, column);
                        }

                    }

                    progress.markProcessedTilesForRow(y, renderParameters);
                }

                column++;
            }

            LOG.info("generateLevelZero: z={}, completed row {} of {}, {}, {}",
                     z, row, boxBounds.getLastRow(), progress, imageProcessorCache.getStats());

            row++;
        }

        // Regenerate the last box generated by a prior run just in case that box's image file
        // was only partially written.  Note that we only bother with this for level 0 data since
        // other (derivative) level data is not as critical.
        if (lastAlreadyGeneratedBox != null) {
            regenerateLevelZeroBox(z, imageProcessorCache, boxMipmapGenerator, lastAlreadyGeneratedBox);
        }

    }

    private void regenerateLevelZeroBox(final Double z,
                                        final ImageProcessorCache imageProcessorCache,
                                        final BoxMipmapGenerator boxMipmapGenerator,
                                        final AlreadyGeneratedState lastAlreadyGeneratedBox)
            throws IOException {

        final File levelZeroFile = BoxMipmapGenerator.getImageFile(format,
                                                                   boxDirectory,
                                                                   0,
                                                                   z.intValue(),
                                                                   lastAlreadyGeneratedBox.row,
                                                                   lastAlreadyGeneratedBox.column);

        LOG.info("regenerateLevelZeroBox: regenerating {} just in case it was only partially generated by the previous run",
                 levelZeroFile.getAbsolutePath());

        generateLevelZeroBox(lastAlreadyGeneratedBox.x,
                             lastAlreadyGeneratedBox.y,
                             z,
                             imageProcessorCache,
                             boxMipmapGenerator,
                             null, // always exclude iGridPaths since path was already added during generateLevelZero loop
                             levelZeroFile,
                             lastAlreadyGeneratedBox.row,
                             lastAlreadyGeneratedBox.column);
    }

    private RenderParameters generateLevelZeroBox(final int x,
                                                  final int y,
                                                  final Double z,
                                                  final ImageProcessorCache imageProcessorCache,
                                                  final BoxMipmapGenerator boxMipmapGenerator,
                                                  final IGridPaths iGridPaths,
                                                  final File levelZeroFile,
                                                  final int row,
                                                  final int column)
            throws IOException {

        final String boxParametersUrl =
                getRenderDataClient().getRenderParametersUrlString(stack, x, y, z, boxWidth, boxHeight, 1.0,
                                                                   boxParameters.filterListName);

        LOG.info("generateLevelZeroBox: loading {}", boxParametersUrl);

        final RenderParameters renderParameters = RenderParameters.loadFromUrl(boxParametersUrl);
        renderParameters.setDoFilter(boxParameters.doFilter);
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

            final BufferedImage levelZeroImage = BoxMipmapGenerator.renderBoxImage(renderParameters,
                                                                                   imageProcessorCache);

            BoxMipmapGenerator.saveImage(levelZeroImage,
                                         levelZeroFile,
                                         boxParameters.label,
                                         format);

            boxMipmapGenerator.addSource(row, column, levelZeroFile);

            if (iGridPaths != null) {
                iGridPaths.addImage(levelZeroFile, row, column);
            }

        } else {
            LOG.info("generateLevelZeroBox: skipping empty box for row {}, column {}", row, column);
        }
        return renderParameters;
    }

    /**
     * Wrapper for capturing location of the last already generated box image.
     */
    private class AlreadyGeneratedState {

        int x;
        int y;
        int row;
        int column;

        AlreadyGeneratedState(final int x,
                              final int y,
                              final int row,
                              final int column) {
            this.x = x;
            this.y = y;
            this.row = row;
            this.column = column;
        }
    }

    /**
     * Utility to support logging of progress during long running layer render process.
     */
    private class Progress {

        private final int numberOfLayerTiles;
        private final Set<String> processedTileIds;
        private final long startTime;
        private final SimpleDateFormat sdf;
        private int currentRowY;

        Progress(final int numberOfLayerTiles) {
            this.numberOfLayerTiles = numberOfLayerTiles;
            this.processedTileIds = new HashSet<>(numberOfLayerTiles * 2);
            this.startTime = System.currentTimeMillis();
            this.sdf = new SimpleDateFormat("HH:mm:ss");
            this.currentRowY = -1;
        }

        void markProcessedTilesForRow(final int rowY,
                                      final RenderParameters renderParameters) {
            final int rowMaxY = rowY + boxHeight;
            if (rowY > currentRowY) {
                currentRowY = rowY;
            }

            if (renderParameters != null) {
                for (final TileSpec tileSpec : renderParameters.getTileSpecs()) {
                    // only add tiles that are completely above the row's max Y value
                    if (tileSpec.getMaxY() <= rowMaxY) {
                        processedTileIds.add(tileSpec.getTileId());
                    }
                }
            }
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();
            final double numberOfProcessedTiles = processedTileIds.size();
            final int percentComplete = (int) (numberOfProcessedTiles * 100 / numberOfLayerTiles);
            sb.append(percentComplete).append("% source tiles processed");
            if (percentComplete > 0) {
                final double msPerTile = (System.currentTimeMillis() - startTime) / numberOfProcessedTiles;
                final long remainingMs = (long) (msPerTile * (numberOfLayerTiles - numberOfProcessedTiles));
                final Date eta = new Date((new Date().getTime()) + remainingMs);
                sb.append(", level 0 ETA is ").append(sdf.format(eta));
            }
            return sb.toString();
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(BoxGenerator.class);
}

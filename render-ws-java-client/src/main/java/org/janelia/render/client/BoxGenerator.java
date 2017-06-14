package org.janelia.render.client;

import com.beust.jcommander.Parameter;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
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
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.alignment.util.LabelImageProcessorCache;
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

    @SuppressWarnings("ALL")
    public static class Parameters extends RenderDataClientParameters {

        // NOTE: --baseDataUrl, --owner, and --project parameters defined in RenderDataClientParameters

        @Parameter(
                names = "--stack",
                description = "Stack name",
                required = true)
        public String stack;

        @Parameter(
                names = "--rootDirectory",
                description = "Root directory for rendered tiles (e.g. /tier2/flyTEM/nobackup/rendered_boxes)",
                required = true)
        private String rootDirectory;

        @Parameter(
                names = "--width",
                description = "Width of each box",
                required = true)
        private Integer width;

        @Parameter(
                names = "--height",
                description = "Height of each box",
                required = true)
        private Integer height;

        @Parameter(
                names = "--maxLevel",
                description = "Maximum mipmap level to generate",
                required = false)
        private Integer maxLevel = 0;

        @Parameter(
                names = "--format",
                description = "Format for rendered boxes",
                required = false)
        private String format = Utils.PNG_FORMAT;

        @Parameter(
                names = "--maxOverviewWidthAndHeight",
                description = "Max width and height of layer overview image (omit or set to zero to disable overview generation)",
                required = false)
        private Integer maxOverviewWidthAndHeight;

        @Parameter(
                names = "--skipInterpolation",
                description = "skip interpolation (e.g. for DMG data)",
                required = false,
                arity = 0)
        private boolean skipInterpolation = false;

        @Parameter(
                names = "--binaryMask",
                description = "use binary mask (e.g. for DMG data)",
                required = false,
                arity = 0)
        private boolean binaryMask = false;

        @Parameter(
                names = "--label",
                description = "Generate single color tile labels instead of actual tile images",
                required = false,
                arity = 0)
        private boolean label = false;

        @Parameter(
                names = "--createIGrid",
                description = "create an IGrid file",
                required = false,
                arity = 0)
        private boolean createIGrid = false;

        @Parameter(
                names = "--forceGeneration",
                description = "Regenerate boxes even if they already exist",
                required = false,
                arity = 0)
        private boolean forceGeneration = false;

        @Parameter(
                names = "--renderGroup",
                description = "Index (1-n) that identifies portion of layer to render (omit if only one job is being used)",
                required = false)
        public Integer renderGroup;

        @Parameter(
                names = "--numberOfRenderGroups",
                description = "Total number of parallel jobs being used to render this layer (omit if only one job is being used)",
                required = false)
        public Integer numberOfRenderGroups;

        public boolean isOverviewNeeded() {
            return ((maxOverviewWidthAndHeight != null) && (maxOverviewWidthAndHeight > 0));
        }

        public Parameters getInstanceForRenderGroup(final int group,
                                                    final int numberOfGroups) {
            Parameters p = new Parameters();

            p.baseDataUrl = this.baseDataUrl;
            p.owner = this.owner;
            p.project = this.project;
            p.stack = this.stack;
            p.rootDirectory = this.rootDirectory;
            p.width = this.width;
            p.height = this.height;
            p.maxLevel = this.maxLevel;
            p.format = this.format;
            p.maxOverviewWidthAndHeight = this.maxOverviewWidthAndHeight;
            p.skipInterpolation = this.skipInterpolation;
            p.binaryMask = this.binaryMask;
            p.label = this.label;
            p.createIGrid = this.createIGrid;
            p.forceGeneration = this.forceGeneration;

            p.renderGroup = group;
            p.numberOfRenderGroups = numberOfGroups;

            return p;
        }

    }

    private final Parameters parameters;

    private final String stack;
    private final String format;
    private final int boxWidth;
    private final int boxHeight;
    private final File boxDirectory;
    private final Integer backgroundRGBColor;
    private final File emptyImageFile;
    private final Bounds stackBounds;

    private transient RenderDataClient rdc;

    public BoxGenerator(final Parameters parameters)
            throws IOException {

        this.parameters = parameters;

        this.stack = parameters.stack;
        this.format = parameters.format;
        this.boxWidth = parameters.width;
        this.boxHeight = parameters.height;

        String boxName = this.boxWidth + "x" + this.boxHeight;
        if (parameters.label) {
            boxName += "-label";
            this.backgroundRGBColor = Color.WHITE.getRGB();
        } else {
            this.backgroundRGBColor = null;
        }

        final Path boxPath = Paths.get(parameters.rootDirectory,
                                       parameters.project,
                                       parameters.stack,
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

        if (parameters.renderGroup != null) {

            if (parameters.numberOfRenderGroups == null) {
                throw new IllegalArgumentException(
                        "numberOfRenderGroups must be specified when renderGroup is specified");
            }

            if (parameters.renderGroup < 1) {
                throw new IllegalArgumentException("renderGroup values start at 1");
            }

            if (parameters.renderGroup > parameters.numberOfRenderGroups) {
                throw new IllegalArgumentException(
                        "numberOfRenderGroups (" + parameters.numberOfRenderGroups +
                        ") must be greater than the renderGroup (" + parameters.renderGroup + ")");
            }

        } else if (parameters.numberOfRenderGroups != null) {
            throw new IllegalArgumentException(
                    "renderGroup (1-n) must be specified when numberOfRenderGroups are specified");
        }

        final StackMetaData stackMetaData = getRenderDataClient().getStackMetaData(this.stack);
        this.stackBounds = stackMetaData.getStats().getStackBounds();
    }

    public RenderDataClient getRenderDataClient() {
        if (rdc == null) {
            rdc = new RenderDataClient(parameters.baseDataUrl,
                                       parameters.owner,
                                       parameters.project);
        }
        return rdc;
    }

    public void createEmptyImageFile()
            throws IOException {

        if (emptyImageFile.exists()) {

            LOG.debug("skipping creation of {} because it already exists", emptyImageFile.getAbsolutePath());

        } else {

            final BufferedImage emptyImage = new BufferedImage(boxWidth, boxHeight, BufferedImage.TYPE_INT_ARGB);

            if (parameters.label) {

                final Graphics2D targetGraphics = emptyImage.createGraphics();
                targetGraphics.setBackground(new Color(backgroundRGBColor));
                targetGraphics.clearRect(0, 0, boxWidth, boxHeight);
                targetGraphics.dispose();

                final BufferedImage emptyLabelImage = BoxMipmapGenerator.convertArgbLabelTo16BitGray(emptyImage);
                if (emptyImageFile.exists()) {
                    LOG.debug("skipping save of {} because it already exists", emptyImageFile.getAbsolutePath());
                } else {
                    Utils.saveImage(emptyLabelImage, emptyImageFile.getAbsolutePath(), format, false, 0.85f);
                }

            } else {

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

        if (parameters.renderGroup != null) {
            boxBounds.setRenderGroup(parameters.renderGroup, parameters.numberOfRenderGroups, parameters.maxLevel);
        }

        final int tileCount;
        final ImageProcessorCache imageProcessorCache;
        if (parameters.label) {

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
                                                                       parameters.label,
                                                                       format,
                                                                       boxWidth,
                                                                       boxHeight,
                                                                       boxDirectory,
                                                                       0,
                                                                       boxBounds.getFirstRow(),
                                                                       boxBounds.getLastRow(),
                                                                       boxBounds.getFirstColumn(),
                                                                       boxBounds.getLastColumn(),
                                                                       parameters.forceGeneration);
        final IGridPaths iGridPaths;
        if (parameters.createIGrid) {
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
        boolean isOverviewGenerated = (! parameters.forceGeneration) && overviewFile.exists();

        if (isOverviewGenerated) {
            LOG.info("generateBoxesForZ: {}, overview {} already generated", z, overviewFile.getAbsolutePath());
        }

        for (int level = 0; level < parameters.maxLevel; level++) {
            boxMipmapGenerator = boxMipmapGenerator.generateNextLevel();
            if (parameters.isOverviewNeeded() && (! isOverviewGenerated)) {
                isOverviewGenerated = boxMipmapGenerator.generateOverview(parameters.maxOverviewWidthAndHeight,
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
            throws URISyntaxException, IOException {

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

                    if (parameters.forceGeneration || (!levelZeroFile.exists())) {

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

        final String parametersUrl =
                getRenderDataClient().getRenderParametersUrlString(stack, x, y, z, boxWidth, boxHeight, 1.0);

        LOG.info("generateLevelZeroBox: loading {}", parametersUrl);

        final RenderParameters renderParameters = RenderParameters.loadFromUrl(parametersUrl);
        renderParameters.setSkipInterpolation(parameters.skipInterpolation);
        renderParameters.setBinaryMask(parameters.binaryMask);
        renderParameters.setBackgroundRGBColor(backgroundRGBColor);

        if (renderParameters.hasTileSpecs()) {

            final BufferedImage levelZeroImage = renderParameters.openTargetImage();

            ArgbRenderer.render(renderParameters, levelZeroImage, imageProcessorCache);

            BoxMipmapGenerator.saveImage(levelZeroImage,
                                         levelZeroFile,
                                         parameters.label,
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

        public AlreadyGeneratedState(final int x,
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

        public Progress(final int numberOfLayerTiles) {
            this.numberOfLayerTiles = numberOfLayerTiles;
            this.processedTileIds = new HashSet<>(numberOfLayerTiles * 2);
            this.startTime = System.currentTimeMillis();
            this.sdf = new SimpleDateFormat("HH:mm:ss");
            this.currentRowY = -1;
        }

        public void markProcessedTilesForRow(final int rowY,
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

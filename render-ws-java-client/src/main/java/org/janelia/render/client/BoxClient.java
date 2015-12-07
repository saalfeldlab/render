package org.janelia.render.client;

import com.beust.jcommander.Parameter;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.janelia.alignment.Render;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.mipmap.BoxMipmapGenerator;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.alignment.util.LabelImageProcessorCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for rendering uniform (but arbitrarily sized) boxes (derived tiles) to disk for one or more layers.
 * A {@link Render} instance is first used to produce the full scale (level 0) boxes.
 * {@link BoxMipmapGenerator} instances are then used to produce any requested down-sampled mipmaps.
 *
 * All generated images have the same dimensions and pixel count and are stored within a
 * CATMAID LargeDataTileSource directory structure that looks like this:
 * <pre>
 *         [root directory]/[tile width]x[tile height]/[level]/[z]/[row]/[col].[format]
 * </pre>
 *
 * Details about the CATMAID LargeDataTileSource can be found at
 * <a href="https://github.com/acardona/CATMAID/blob/master/django/applications/catmaid/static/js/tilesource.js">
 *     https://github.com/acardona/CATMAID/blob/master/django/applications/catmaid/static/js/tilesource.js
 * </a>).
 *
 * @author Eric Trautman
 */
public class BoxClient {

    @SuppressWarnings("ALL")
    private static class Parameters extends RenderDataClientParameters {

        // NOTE: --baseDataUrl, --owner, and --project parameters defined in RenderDataClientParameters

        @Parameter(names = "--stack", description = "Stack name", required = true)
        private String stack;

        @Parameter(names = "--rootDirectory", description = "Root directory for rendered tiles (e.g. /tier2/flyTEM/nobackup/rendered_boxes/fly_pilot/20141216_863_align)", required = true)
        private String rootDirectory;

        @Parameter(names = "--width", description = "Width of each box", required = true)
        private Integer width;

        @Parameter(names = "--height", description = "Height of each box", required = true)
        private Integer height;

        @Parameter(names = "--maxLevel", description = "Maximum mipmap level to generate (default is 0)", required = false)
        private Integer maxLevel = 0;

        @Parameter(names = "--format", description = "Format for rendered boxes (default is PNG)", required = false)
        private String format = Utils.PNG_FORMAT;

        @Parameter(names = "--maxOverviewWidthAndHeight", description = "Max width and height of layer overview image (omit or set to zero to disable overview generation)", required = false)
        private Integer maxOverviewWidthAndHeight;

        @Parameter(names = "--skipInterpolation", description = "skip interpolation (e.g. for DMG data)", required = false, arity = 0)
        private boolean skipInterpolation = false;

        @Parameter(names = "--binaryMask", description = "use binary mask (e.g. for DMG data)", required = false, arity = 0)
        private boolean binaryMask = false;

        @Parameter(names = "--label", description = "Generate single color tile labels instead of actual tile images", required = false, arity = 0)
        private boolean label = false;

        @Parameter(names = "--createIGrid", description = "create an IGrid file", required = false, arity = 0)
        private boolean createIGrid = false;

        @Parameter(names = "--forceGeneration", description = "Regenerate boxes even if they already exist", required = false, arity = 0)
        private boolean forceGeneration = false;

        @Parameter(names = "--renderGroup", description = "Index (1-n) that identifies portion of layer to render (omit if only one job is being used)", required = false)
        private Integer renderGroup;

        @Parameter(names = "--numberOfRenderGroups", description = "Total number of parallel jobs being used to render this layer (omit if only one job is being used)", required = false)
        private Integer numberOfRenderGroups;

        @Parameter(description = "Z values for layers to render", required = true)
        private List<Double> zValues;

        public boolean isOverviewNeeded() {
            return ((maxOverviewWidthAndHeight != null) && (maxOverviewWidthAndHeight > 0));
        }
    }

    /**
     * @param  args  see {@link Parameters} for command line argument details.
     */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final BoxClient client = new BoxClient(parameters);
                client.createEmptyImageFile();

                for (final Double z : parameters.zValues) {
                    client.generateBoxesForZ(z);
                }
            }
        };
        clientRunner.run();
    }

    private final Parameters params;

    private final String stack;
    private final String format;
    private final int boxWidth;
    private final int boxHeight;
    private final File boxDirectory;
    private final Integer backgroundRGBColor;
    private final File emptyImageFile;
    private final Bounds stackBounds;
    private final RenderDataClient renderDataClient;

    public BoxClient(final Parameters params)
            throws IOException {

        this.params = params;
        this.stack = params.stack;
        this.format = params.format;
        this.boxWidth = params.width;
        this.boxHeight = params.height;

        String boxName = this.boxWidth + "x" + this.boxHeight;
        if (params.label) {
            boxName += "-label";
            this.backgroundRGBColor = Color.WHITE.getRGB();
        } else {
            this.backgroundRGBColor = null;
        }

        final Path boxPath = Paths.get(params.rootDirectory,
                                       params.project,
                                       params.stack,
                                       boxName).toAbsolutePath();

        this.boxDirectory = boxPath.toFile();

        final File stackDirectory = this.boxDirectory.getParentFile();

        if (! stackDirectory.exists()) {
            throw new IllegalArgumentException("missing stack directory " + stackDirectory);
        }

        if (! stackDirectory.canWrite()) {
            throw new IllegalArgumentException("not allowed to write to stack directory " + stackDirectory);
        }

        this.emptyImageFile = new File(boxDirectory.getAbsolutePath(),
                                       "empty." + format.toLowerCase());

        if (params.renderGroup != null) {

            if (params.numberOfRenderGroups == null) {
                throw new IllegalArgumentException(
                        "numberOfRenderGroups must be specified when renderGroup is specified");
            }

            if (params.renderGroup < 1) {
                throw new IllegalArgumentException("renderGroup values start at 1");
            }

            if (params.renderGroup > params.numberOfRenderGroups) {
                throw new IllegalArgumentException(
                        "numberOfRenderGroups (" + params.numberOfRenderGroups +
                        ") must be greater than the renderGroup (" + params.renderGroup + ")");
            }

        } else if (params.numberOfRenderGroups != null) {
            throw new IllegalArgumentException(
                    "renderGroup (1-n) must be specified when numberOfRenderGroups are specified");
        }

        this.renderDataClient = params.getClient();

        final StackMetaData stackMetaData = this.renderDataClient.getStackMetaData(this.stack);
        this.stackBounds = stackMetaData.getStats().getStackBounds();
    }

    public void createEmptyImageFile()
            throws IOException {

        if (emptyImageFile.exists()) {

            LOG.debug("skipping creation of {} because it already exists", emptyImageFile.getAbsolutePath());

        } else {

            final BufferedImage emptyImage = new BufferedImage(boxWidth, boxHeight, BufferedImage.TYPE_INT_ARGB);

            if (params.label) {

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

        LOG.info("generateBoxesForZ: {}, entry, boxDirectory={}, dataClient={}",
                 z, boxDirectory, renderDataClient);

        final Bounds layerBounds = renderDataClient.getLayerBounds(stack, z);
        final SectionBoxBounds boxBounds = new SectionBoxBounds(z, boxWidth, boxHeight, layerBounds);

        if (params.renderGroup != null) {
            boxBounds.setRenderGroup(params.renderGroup, params.numberOfRenderGroups, params.maxLevel);
        }

        final List<TileBounds> tileBoundsList = renderDataClient.getTileBounds(stack, z);
        final int tileCount = tileBoundsList.size();

        final TileBounds firstTileBounds = tileBoundsList.get(0);
        final TileSpec firstTileSpec = renderDataClient.getTile(stack, firstTileBounds.getTileId());

        LOG.info("generateBoxesForZ: {}, layerBounds={}, boxBounds={}, tileCount={}",
                 z, layerBounds, boxBounds, tileCount);

        final ImageProcessorCache imageProcessorCache;
        if (params.label) {
            imageProcessorCache = new LabelImageProcessorCache(ImageProcessorCache.DEFAULT_MAX_CACHED_PIXELS,
                                                               true,
                                                               false,
                                                               firstTileSpec.getWidth(),
                                                               firstTileSpec.getHeight(),
                                                               tileCount);
        } else {
            imageProcessorCache = new ImageProcessorCache();
        }

        BoxMipmapGenerator boxMipmapGenerator = new BoxMipmapGenerator(z.intValue(),
                                                                       params.label,
                                                                       format,
                                                                       boxWidth,
                                                                       boxHeight,
                                                                       boxDirectory,
                                                                       0,
                                                                       boxBounds.getFirstRow(),
                                                                       boxBounds.getLastRow(),
                                                                       boxBounds.getFirstColumn(),
                                                                       boxBounds.getLastColumn(),
                                                                       params.forceGeneration);
        final IGridPaths iGridPaths;
        if (params.createIGrid) {
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
        boolean isOverviewGenerated = (! params.forceGeneration) && overviewFile.exists();

        if (isOverviewGenerated) {
            LOG.info("generateBoxesForZ: {}, overview {} already generated", z, overviewFile.getAbsolutePath());
        }

        for (int level = 0; level < params.maxLevel; level++) {
            boxMipmapGenerator = boxMipmapGenerator.generateNextLevel();
            if (params.isOverviewNeeded() && (! isOverviewGenerated)) {
                isOverviewGenerated = boxMipmapGenerator.generateOverview(params.maxOverviewWidthAndHeight,
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

        RenderParameters renderParameters;
        String parametersUrl;
        BufferedImage levelZeroImage;
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

                    if (params.forceGeneration || (!levelZeroFile.exists())) {
                        parametersUrl =
                                renderDataClient.getRenderParametersUrlString(stack, x, y, z, boxWidth, boxHeight, 1.0);

                        LOG.info("generateLevelZero: z={}, loading {}", z, parametersUrl);

                        renderParameters = RenderParameters.loadFromUrl(parametersUrl);
                        renderParameters.setSkipInterpolation(params.skipInterpolation);
                        renderParameters.setBinaryMask(params.binaryMask);
                        renderParameters.setBackgroundRGBColor(backgroundRGBColor);

                        if (renderParameters.hasTileSpecs()) {

                            levelZeroImage = renderParameters.openTargetImage();

                            Render.render(renderParameters, levelZeroImage, imageProcessorCache);

                            BoxMipmapGenerator.saveImage(levelZeroImage,
                                                         levelZeroFile,
                                                         params.label,
                                                         format);

                            boxMipmapGenerator.addSource(row, column, levelZeroFile);

                            if (iGridPaths != null) {
                                iGridPaths.addImage(levelZeroFile, row, column);
                            }

                        } else {
                            LOG.info("generateLevelZero: z={}, skipping empty box for row {}, column {})", z, row, column);
                        }

                    } else {

                        LOG.info("{} already generated", levelZeroFile.getAbsolutePath());

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
        private double currentRowPartialTileSum;

        public Progress(final int numberOfLayerTiles) {
            this.numberOfLayerTiles = numberOfLayerTiles;
            this.processedTileIds = new HashSet<>(numberOfLayerTiles * 2);
            this.startTime = System.currentTimeMillis();
            this.sdf = new SimpleDateFormat("HH:mm:ss");
            this.currentRowY = -1;
            this.currentRowPartialTileSum = 0;
        }

        public void markProcessedTilesForRow(final int rowY,
                                             final RenderParameters renderParameters) {
            final int rowMaxY = rowY + boxHeight;
            if (rowY > currentRowY) {
                currentRowY = rowY;
                currentRowPartialTileSum = 0;
            }

            if (renderParameters != null) {
                for (final TileSpec tileSpec : renderParameters.getTileSpecs()) {
                    // only add tiles that are completely above the row's max Y value
                    if (tileSpec.getMaxY() <= rowMaxY) {
                        processedTileIds.add(tileSpec.getTileId());
                    } else {
                        currentRowPartialTileSum += (rowMaxY - tileSpec.getMinY()) / (double) tileSpec.getHeight();
                    }
                }
            }
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();
            final double numberOfProcessedTiles = processedTileIds.size() + currentRowPartialTileSum;
            final int percentComplete = (int) (numberOfProcessedTiles * 100 / numberOfLayerTiles);
            sb.append(percentComplete).append("% source tiles processed");
            if (numberOfProcessedTiles > 0) {
                final double msPerTile = (System.currentTimeMillis() - startTime) / numberOfProcessedTiles;
                final long remainingMs = (long) (msPerTile * (numberOfLayerTiles - numberOfProcessedTiles));
                final Date eta = new Date((new Date().getTime()) + remainingMs);
                sb.append(", ETA is ").append(sdf.format(eta));
            }
            return sb.toString();
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(BoxClient.class);
}

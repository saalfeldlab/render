package org.janelia.render.client;

import ij.ImagePlus;
import ij.process.ImageProcessor;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import mpicbg.trakem2.util.Downsampler;

import org.janelia.alignment.Render;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for rendering uniform (but arbitrarily sized) boxes (derived tiles) to disk for one or more layers.
 * The box directory structure is organized as follows:
 * <pre>
 *         [root directory]/[tile width]x[tile height]/[level]/[row]/[col].[format]
 * </pre>
 *
 * If requested, an overview box for each layer is also generated in
 * <pre>
 *         [root directory]/[tile width]x[tile height]/small/[layer].[format]
 * </pre>
 *
 * This structure can be used by the CATMAID LargeDataTileSource (see
 * <a href="https://github.com/acardona/CATMAID/blob/master/django/applications/catmaid/static/js/tilesource.js">
 *     https://github.com/acardona/CATMAID/blob/master/django/applications/catmaid/static/js/tilesource.js
 * </a>).
 *
 * @author Eric Trautman
 */
public class BoxClient {

    /**
     * @param  args  see {@link BoxClientParameters} for command line argument details.
     */
    public static void main(String[] args) {
        try {

            final BoxClientParameters params = BoxClientParameters.parseCommandLineArgs(args);

            if (params.displayHelp()) {

                params.showUsage();

            } else {

                LOG.info("main: entry, params={}", params);

                final BoxClient client = new BoxClient(params);

                for (Double z : params.getzValues()) {
                    client.generateBoxesForZ(z);
                }

            }

        } catch (final Throwable t) {
            LOG.error("main: caught exception", t);
        }
    }

    private final BoxClientParameters params;

    private final String stack;
    private final int boxWidth;
    private final int boxHeight;
    private final File stackDirectory;
    private final boolean createOverview;
    private final RenderDataClient renderDataClient;

    public BoxClient(final BoxClientParameters params) {

        this.params = params;
        this.stack = params.getStack();
        this.boxWidth = params.getWidth();
        this.boxHeight = params.getHeight();

        final Path stackPath = Paths.get(params.getRootDirectory(),
                                         params.getProject(),
                                         params.getStack()).toAbsolutePath();

        this.stackDirectory = stackPath.toFile();

        if (! stackDirectory.exists()) {
            throw new IllegalArgumentException("missing stack directory " + stackDirectory);
        }

        if (! stackDirectory.canWrite()) {
            throw new IllegalArgumentException("not allowed to write to stack directory " + stackDirectory);
        }

        this.createOverview = ((params.getOverviewWidth() != null) &&
                               (params.getOverviewWidth() > 0) &&
                               (params.getMaxLevel() > 3)); // only create overview if small mipmaps are being created

        this.renderDataClient = new RenderDataClient(params.getBaseDataUrl(), params.getOwner(), params.getProject());
    }

    public void generateBoxesForZ(final Double z)
            throws Exception {

        LOG.info("generateBoxesForZ: {}, entry, createOverview={}, stackDirectory={}, dataClient={}",
                 z, createOverview, stackDirectory, renderDataClient);

        final Bounds layerBounds = renderDataClient.getLayerBounds(stack, z);
        final BoxBounds boxBounds = new BoxBounds(z, layerBounds);
        final List<TileBounds> tileBoundsList = renderDataClient.getTileBounds(stack, z);
        final Progress progress = new Progress(tileBoundsList.size());

        LOG.info("generateBoxesForZ: {}, layerBounds={}, boxBounds={}", z, layerBounds, boxBounds);

        final ImageProcessorCache imageProcessorCache = new ImageProcessorCache();

        OverviewImage overviewImage = null;

        RenderParameters renderParameters;
        String parametersUrl;
        BufferedImage levelZeroImage;
        int row = boxBounds.firstRow;
        int column;
        for (int y = boxBounds.firstY; y < layerBounds.getMaxY(); y += boxHeight) {

            column = boxBounds.firstColumn;

            for (int x = boxBounds.firstX; x < layerBounds.getMaxX(); x += boxWidth) {

                parametersUrl = renderDataClient.getRenderParametersUrlString(stack, x, y, z, boxWidth, boxHeight, 1.0);
                renderParameters = RenderParameters.loadFromUrl(parametersUrl);

                if (renderParameters.hasTileSpecs()) {

                    levelZeroImage = renderParameters.openTargetImage();

                    Render.render(renderParameters, levelZeroImage, imageProcessorCache);

                    saveImage(levelZeroImage, 0, boxBounds.z, row, column);

                    overviewImage = saveMipmapsAndAddToOverview(boxBounds,
                                                                levelZeroImage,
                                                                row,
                                                                column,
                                                                overviewImage);

                    progress.markProcessedTilesForRow(y, renderParameters);

                } else {
                    LOG.info("generateBoxesForZ: {}, skipping empty box for row {}, column {})", z, row, column);
                }

                column++;
            }

            LOG.info("generateBoxesForZ: {}, completed row {} of {}, {}, {}",
                     z, row, boxBounds.lastRow, progress, imageProcessorCache.getStats());

            row++;
        }

        if (overviewImage != null) {
            overviewImage.saveImage(params.getOverviewWidth());
        }

        LOG.info("generateBoxesForZ: {}, exit, cache stats: {}", z, imageProcessorCache.getStats());
    }

    private void saveImage(final BufferedImage image,
                           final int level,
                           final int z,
                           final int row,
                           final int col)
            throws IOException {

        final Path imageDirPath = Paths.get(stackDirectory.getAbsolutePath(),
                                            boxWidth + "x" + boxHeight,
                                            String.valueOf(level),
                                            String.valueOf(z),
                                            String.valueOf(row));

        final File outputFile = new File(imageDirPath.toFile(),
                                         col + "." + params.getFormat().toLowerCase());
        final File parentDirectory = outputFile.getParentFile();
        if (! parentDirectory.exists()) {
            if (! parentDirectory.mkdirs()) {
                throw new IOException("failed to create " + parentDirectory.getAbsolutePath());
            }
        }

        Utils.saveImage(image, outputFile.getAbsolutePath(), params.getFormat(), true, 0.85f);
    }

    private OverviewImage saveMipmapsAndAddToOverview(final BoxBounds boxBounds,
                                                      final BufferedImage levelZeroImage,
                                                      final int row,
                                                      final int column,
                                                      OverviewImage overviewImage)
            throws IOException {

        final ImagePlus levelZeroImagePlus = new ImagePlus("", levelZeroImage);

        ImageProcessor currentProcessor = levelZeroImagePlus.getProcessor();
        ImageProcessor downSampledProcessor;
        BufferedImage downSampledImage;

        for (int level = 1; level < (params.getMaxLevel() + 1); level++) {

            downSampledProcessor = Downsampler.downsampleImageProcessor(currentProcessor, 1);
            downSampledImage = downSampledProcessor.getBufferedImage();
            saveImage(downSampledImage, level, boxBounds.z, row, column);

            if (createOverview && (level == params.getMaxLevel())) {

                if (overviewImage == null) {
                    overviewImage = new OverviewImage(downSampledImage.getWidth(),
                                                      downSampledImage.getHeight(),
                                                      boxBounds);
                }

                overviewImage.addTile(downSampledImage, column, row);
            }

            currentProcessor = downSampledProcessor;
        }

        return overviewImage;
    }

    private class BoxBounds {

        public final int z;

        public final int firstColumn;
        public final int firstX;
        public final int firstRow;
        public final int firstY;

        public final int lastColumn;
        public final int lastX;
        public final int lastRow;
        public final int lastY;

        private BoxBounds(Double z,
                          Bounds layerBounds) {

            this.z = z.intValue();

            this.firstColumn = (int) (layerBounds.getMinX() / boxWidth);
            this.firstX = firstColumn * boxWidth;
            this.firstRow = (int) (layerBounds.getMinY() / boxHeight);
            this.firstY = firstRow * boxHeight;

            this.lastColumn = (int) (layerBounds.getMaxX() / boxWidth);
            this.lastX = (lastColumn + 1) * boxWidth - 1;
            this.lastRow = (int) (layerBounds.getMaxY() / boxHeight);
            this.lastY = (lastRow + 1) * boxHeight - 1;
        }

        @Override
        public String toString() {
            return " columns " + firstColumn + " to " + lastColumn + " (x: " + firstX + " to " + lastX +
                   "), rows " + firstRow + " to " + lastRow + " (y: " + firstY + " to " + lastY + ")";
        }
    }

    private class Progress {

        private int numberOfLayerTiles;
        private Set<String> processedTileIds;
        private long startTime;
        private SimpleDateFormat sdf;
        private int currentRowY;
        private double currentRowPartialTileSum;

        public Progress(final int numberOfLayerTiles) {
            this.numberOfLayerTiles = numberOfLayerTiles;
            this.processedTileIds = new HashSet<String>(numberOfLayerTiles * 2);
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

            for (TileSpec tileSpec : renderParameters.getTileSpecs()) {
                // only add tiles that are completely above the row's max Y value
                if (tileSpec.getMaxY() <= rowMaxY) {
                    processedTileIds.add(tileSpec.getTileId());
                } else {
                    currentRowPartialTileSum += (rowMaxY - tileSpec.getMinY()) / (double) tileSpec.getHeight();
                }
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
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

    private class OverviewImage {

        private BufferedImage image;
        private Graphics2D graphics;
        private int tileWidth;
        private int tileHeight;
        private File overviewFile;

        public OverviewImage(final int tileWidth,
                             final int tileHeight,
                             final BoxBounds boxBounds) {

            this.tileWidth = tileWidth;
            this.tileHeight = tileHeight;

            this.image = new BufferedImage((tileWidth * boxBounds.lastColumn),
                                           (tileHeight * boxBounds.lastRow),
                                           BufferedImage.TYPE_INT_ARGB);
            this.graphics = image.createGraphics();

            final Path imageDirPath = Paths.get(stackDirectory.getAbsolutePath(),
                                                boxWidth + "x" + boxHeight,
                                                "small");

            this.overviewFile = new File(imageDirPath.toFile(),
                                         boxBounds.z + "." + params.getFormat().toLowerCase()).getAbsoluteFile();

            final File parentDirectory = this.overviewFile.getParentFile();
            if (! parentDirectory.exists()) {
                if (! parentDirectory.mkdirs()) {
                    throw new IllegalArgumentException("failed to create " + parentDirectory.getAbsolutePath());
                }
            }

            LOG.info("OverviewImage: tileWidth={}, tileHeight={}, imageWidth={}, imageHeight={}, file={}",
                     tileWidth, tileHeight, image.getWidth(), image.getHeight(), overviewFile.getAbsolutePath());
        }

        public void addTile(final BufferedImage tileImage,
                            final int column,
                            final int row) {

            graphics.drawImage(tileImage, (column * tileWidth), (row * tileHeight), null);
        }

        private void saveImage(final int scaledWidth)
                throws IOException {

            final int scaledHeight = (int) (((double) scaledWidth / image.getWidth()) * image.getHeight());

            final BufferedImage scaledImage = new BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_ARGB);

            final Graphics2D g2 = scaledImage.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(image, 0, 0, scaledWidth, scaledHeight, null);
            g2.dispose();

            Utils.saveImage(image, overviewFile.getAbsolutePath(), params.getFormat(), true, 0.85f);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(BoxClient.class);
}

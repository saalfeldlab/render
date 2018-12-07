package org.janelia.alignment.mipmap;

import ij.ImagePlus;
import ij.process.ImageProcessor;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import mpicbg.trakem2.util.Downsampler;

import org.janelia.alignment.ArgbRenderer;
import org.janelia.alignment.LabelRenderer;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.alignment.util.LabelImageProcessorCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates down-sampled mipmaps to disk and caches the results in a 'next level' generator
 * so that they can be used iteratively.
 *
 * All generated images have the same dimensions and pixel count and are stored within a
 * CATMAID LargeDataTileSource directory structure that looks like this:
 * <pre>
 *         [box (root) directory]/[level]/[row]/[col].[format]
 * </pre>
 *
 * An overview box for the source images (presumably all part of a specific layer) can be generated to:
 * <pre>
 *         [box (root) directory]/small/[layer].[format]
 * </pre>
 *
 * Details about the CATMAID LargeDataTileSource can be found at
 * <a href="https://github.com/acardona/CATMAID/blob/master/django/applications/catmaid/static/js/tilesource.js">
 *     https://github.com/acardona/CATMAID/blob/master/django/applications/catmaid/static/js/tilesource.js
 * </a>).
 *
 * @author Eric Trautman
 */
public class BoxMipmapGenerator {

    private final int z;
    private final boolean isLabel;
    private final String format;
    private final int boxWidth;
    private final int boxHeight;
    private final File boxDirectory;
    private final int sourceLevel;
    private final int firstSourceRow;
    private final int lastSourceRow;
    private final int firstSourceColumn;
    private final int lastSourceColumn;
    private final boolean forceGeneration;

    private final List<List<File>> rowFileLists; // row -> column
    private final List<File> emptyRow;

    /**
     * Basic constructor.
     *
     * @param  z                 z value for the layer being processed.
     * @param  isLabel           indicates that the images are labels and not standard images.
     * @param  format            format of all generated image files.
     * @param  boxWidth          width for all generated image files.
     * @param  boxHeight         height for all generated image files.
     * @param  boxDirectory      parent directory for all generated image files.
     * @param  sourceLevel       scaling level for the source images to be down-sampled.
     * @param  firstSourceRow    number of row (0-based) containing upper left tile in layer (or portion of layer).
     * @param  lastSourceRow     number of row (0-based) containing bottom right tile in layer (or portion of layer).
     * @param  firstSourceColumn number of column (0-based) containing upper left tile in layer (or portion of layer).
     * @param  lastSourceColumn  number of column (0-based) containing bottom right tile in layer (or portion of layer).
     */
    public BoxMipmapGenerator(final int z,
                              final boolean isLabel,
                              final String format,
                              final int boxWidth,
                              final int boxHeight,
                              final File boxDirectory,
                              final int sourceLevel,
                              final int firstSourceRow,
                              final int lastSourceRow,
                              final int firstSourceColumn,
                              final int lastSourceColumn,
                              final boolean forceGeneration) {
        this.z = z;
        this.isLabel = isLabel;
        this.format = format;
        this.boxWidth = boxWidth;
        this.boxHeight = boxHeight;
        this.boxDirectory = boxDirectory;
        this.sourceLevel = sourceLevel;
        this.firstSourceRow = (firstSourceRow / 2) * 2; // ensure first row is a multiple of 2
        this.lastSourceRow = lastSourceRow;
        this.firstSourceColumn = (firstSourceColumn / 2) * 2; // ensure first column is a multiple of 2
        this.lastSourceColumn = lastSourceColumn;
        this.forceGeneration = forceGeneration;

        // make sure all column lists are the same length
        this.rowFileLists = new ArrayList<>(this.lastSourceRow + 1);
        List<File> columnFiles;
        for (int row = 0; row <= this.lastSourceRow; row++) {
            columnFiles = new ArrayList<>(this.lastSourceColumn + 1);
            for (int column = 0; column <= this.lastSourceColumn; column++) {
                columnFiles.add(null);
            }
            this.rowFileLists.add(columnFiles);
        }

        emptyRow = new ArrayList<>(this.lastSourceColumn + 1);
        for (int column = 0; column <= this.lastSourceColumn; column++) {
            emptyRow.add(null);
        }
    }

    /**
     * @return scaling level for this generator's source images.
     */
    int getSourceLevel() {
        return sourceLevel;
    }

    /**
     * Adds the source file to the specified row and column of this generator.
     *
     * @param  sourceRow     number of row containing the source image (in context of source scaling level).
     * @param  sourceColumn  number of column containing the source image (in context of source scaling level).
     * @param  source        image to add.
     */
    public void addSource(final int sourceRow,
                          final int sourceColumn,
                          final File source) {
        List<File> rowFiles = rowFileLists.get(sourceRow);
        if (rowFiles == null) {
            rowFiles = new ArrayList<>();
            rowFileLists.add(sourceRow, rowFiles);
        }
        rowFiles.add(sourceColumn, source);
    }

    /**
     * Generates next level (n+1) of the mipmap pyramid and saves the resulting images to disk.
     *
     * @return a generator that can be used to generate the subsequent level (n+2) of the mipmap pyramid.
     *
     * @throws IOException
     *   if any images cannot be created.
     */
    public BoxMipmapGenerator generateNextLevel()
            throws IOException {

        final int scaledLevel = sourceLevel + 1;

        LOG.info("generateNextLevel: generating level {} mipmaps for z={}", scaledLevel, z);

        final BoxMipmapGenerator nextLevelGenerator =  new BoxMipmapGenerator(z,
                                                                              isLabel,
                                                                              format,
                                                                              boxWidth,
                                                                              boxHeight,
                                                                              boxDirectory,
                                                                              scaledLevel,
                                                                              (firstSourceRow / 2),
                                                                              (lastSourceRow / 2),
                                                                              (firstSourceColumn / 2),
                                                                              (lastSourceColumn / 2),
                                                                              forceGeneration);
        List<File> firstRowFiles;
        int secondRow;
        List<File> secondRowFiles;
        MipmapSource mipmapSource;
        File scaledFile;
        int scaledRow;
        int scaledColumn;

        for (int sourceRow = firstSourceRow; sourceRow <= lastSourceRow; sourceRow += 2) {

            firstRowFiles = rowFileLists.get(sourceRow);

            secondRow = sourceRow + 1;

            if (secondRow <= lastSourceRow) {
                secondRowFiles = rowFileLists.get(secondRow);
            } else {
                secondRowFiles = emptyRow;
            }

            for (int sourceColumn = firstSourceColumn; sourceColumn <= lastSourceColumn; sourceColumn += 2) {

                mipmapSource = new MipmapSource(firstRowFiles,
                                                secondRowFiles,
                                                sourceColumn,
                                                lastSourceColumn);

                scaledRow = sourceRow / 2;
                scaledColumn = sourceColumn / 2;

                if (mipmapSource.hasContent()) {

                    scaledFile = getImageFile(format, boxDirectory, scaledLevel, z, scaledRow, scaledColumn);

                    if (forceGeneration || (!scaledFile.exists())) {
                        mipmapSource.saveScaledFile(scaledFile);
                    } else {
                        LOG.debug("{} already generated", scaledFile.getAbsolutePath());
                    }

                } else {
                    scaledFile = null; // ensure file is null for next level generator
                }

                nextLevelGenerator.addSource(scaledRow, scaledColumn, scaledFile);
            }
        }

        return nextLevelGenerator;
    }

    /**
     * Generates a CATMAID overview image from this generator's source image.
     * An overview image will only be generated if this generator contains one and only one source image.
     *
     * @param  maxOverviewWidthAndHeight  max width and height for overview image.
     * @param  stackBounds                full scale (level 0) bounds for stack.
     *
     * @return true if the overview image file was generated;
     *         false if generation was skipped because this generator has more than one source image.
     *
     * @throws IOException
     *   if the overview image cannot be saved to disk.
     */
    public boolean generateOverview(final int maxOverviewWidthAndHeight,
                                    final Bounds stackBounds,
                                    final File overviewFile)
            throws IOException {

        boolean isGenerated = false;

        if ((lastSourceRow == 0) && (lastSourceColumn == 0) &&  // skip overview if there is > 1 source image
            (! isLabel)) {                                      // skip overview for label runs

            double scaledStackMaxX = stackBounds.getMaxX();
            double scaledStackMaxY = stackBounds.getMaxY();
            for (int level = 0; level < sourceLevel; level++) {
                scaledStackMaxX = scaledStackMaxX / 2;
                scaledStackMaxY = scaledStackMaxY / 2;
            }

            LOG.info("generateOverview: generating overview for z={}, maxOverviewWidthAndHeight={}, sourceLevel={}, scaledStackMaxX={}, scaledStackMaxY={}",
                     z, maxOverviewWidthAndHeight, sourceLevel, scaledStackMaxX, scaledStackMaxY);

            makeDirectories(overviewFile.getCanonicalFile());

            final List<File> firstRowFiles = rowFileLists.get(0);
            final File sourceFile = firstRowFiles.get(0);
            BufferedImage sourceImage = Utils.openImage(sourceFile.getAbsolutePath());

            // clip source image if it is bigger than scaled stack bounds
            if ((scaledStackMaxX <= sourceImage.getWidth()) &&
                (scaledStackMaxY <= sourceImage.getHeight())) {
                sourceImage = sourceImage.getSubimage(0, 0, (int) scaledStackMaxX, (int) scaledStackMaxY);
            }

            final double scale;
            if (stackBounds.getMaxX() > stackBounds.getMaxY()) {
                scale = (double) maxOverviewWidthAndHeight / sourceImage.getWidth();
            } else {
                scale = (double) maxOverviewWidthAndHeight / sourceImage.getHeight();
            }

            final int overviewWidth = (int) (scale * sourceImage.getWidth());
            final int overviewHeight = (int) (scale * sourceImage.getHeight());

            final BufferedImage overviewImage =
                    new BufferedImage(overviewWidth, overviewHeight, BufferedImage.TYPE_INT_ARGB);

            final Graphics2D overviewGraphics = overviewImage.createGraphics();
            overviewGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            overviewGraphics.drawImage(sourceImage, 0, 0, overviewWidth, overviewHeight, null);
            overviewGraphics.dispose();

            Utils.saveImage(overviewImage, overviewFile.getAbsolutePath(), format, true, 0.85f);

            isGenerated = true;

        } else {
            LOG.info("generateOverview: skipping generation, z={}, sourceLevel={}, lastSourceRow={}, lastSourceColumn={}",
                     z, sourceLevel, lastSourceRow, lastSourceColumn, z);
        }

        return isGenerated;
    }

    /**
     * @return rendered box image based upon the specified parameters.
     */
    public static BufferedImage renderBoxImage(final RenderParameters renderParameters,
                                               final ImageProcessorCache imageProcessorCache) {
        final BufferedImage boxImage;

        if (imageProcessorCache instanceof LabelImageProcessorCache) {

            boxImage = renderParameters.openTargetImage(BufferedImage.TYPE_USHORT_GRAY);
            LabelRenderer.render(renderParameters, boxImage, (LabelImageProcessorCache) imageProcessorCache);

        } else {

            boxImage = renderParameters.openTargetImage(BufferedImage.TYPE_INT_ARGB);
            ArgbRenderer.render(renderParameters, boxImage, imageProcessorCache);

            // To test rendering on machines that have access to the web service
            // but not to the source images (e.g. a laptop),
            // uncomment the following lines and then comment out the ArgbRenderer call above.
//                    final BoundingBoxRenderer renderer = new BoundingBoxRenderer(renderParameters, Color.WHITE, 100);
//                    renderer.render(boxImage);

        }

        return boxImage;
    }

    /**
     * @param  format        image format.
     * @param  boxDirectory  root directory for the image (e.g. /project/stack/width-x-height)
     * @param  level         scale level for the image.
     * @param  z             z value for layer containing the image.
     * @param  row           row containing image.
     * @param  column        column containing image.
     *
     * @return the file that was saved for the image.
     */
    public static File getImageFile(final String format,
                                    final File boxDirectory,
                                    final int level,
                                    final int z,
                                    final int row,
                                    final int column) {

        final Path imageDirPath = Paths.get(boxDirectory.getAbsolutePath(),
                                            String.valueOf(level),
                                            String.valueOf(z),
                                            String.valueOf(row));
        final String imageFileName = column + "." + format.toLowerCase();
        return new File(imageDirPath.toFile(), imageFileName).getAbsoluteFile();
    }

    /**
     * Utility to save an image.
     *
     * @param  image         image to save.
     * @param  imageFile     file for image.
     * @param  isLabel       indicates that the image is a label and not a standard image.
     * @param  format        format in which to save the image.
     *
     * @throws IOException
     *   if the image cannot be saved for any reason.
     */
    public static void saveImage(final BufferedImage image,
                                 final File imageFile,
                                 final boolean isLabel,
                                 final String format)
            throws IOException {

        makeDirectories(imageFile.getCanonicalFile());

        if (isLabel) {
            Utils.saveImage(image, imageFile.getAbsolutePath(), format, false, 0.85f);
        } else {
            Utils.saveImage(image, imageFile.getAbsolutePath(), format, true, 0.85f);
        }
    }

    /**
     * Utility to ensure that all parent directories are created for the specified file.
     *
     * @param  imageFile   image file being saved.
     *
     * @throws IOException
     *   if the file's parent directories cannot be created.
     */
    private static void makeDirectories(final File imageFile)
            throws IOException {
        final File parentDirectory = imageFile.getParentFile();
        if (! parentDirectory.exists()) {
            if (! parentDirectory.mkdirs()) {
                // check for existence again in case another parallel process already created the directory
                if (! parentDirectory.exists()) {
                    throw new IOException("failed to create " + parentDirectory.getAbsolutePath());
                }
            }
        }
    }

    /**
     * Container for the (up to) 4 source files used to generate a down-sampled mipmap.
     */
    private class MipmapSource {

        private final File upperLeft;
        private final File upperRight;
        private final File lowerLeft;
        private final File lowerRight;

        /**
         *
         * @param  firstRowFiles     first row of source files for this mipmap.
         * @param  secondRowFiles    second row of source files for this mipmap.
         * @param  sourceColumn      column index for the upper left tile.
         * @param  lastSourceColumn  index of the largest column with tiles.
         */
        MipmapSource(final List<File> firstRowFiles,
                     final List<File> secondRowFiles,
                     final int sourceColumn,
                     final int lastSourceColumn) {

            final int secondColumn = sourceColumn + 1;

            this.upperLeft = firstRowFiles.get(sourceColumn);
            this.lowerLeft = secondRowFiles.get(sourceColumn);

            if (secondColumn <= lastSourceColumn) {
                this.upperRight = firstRowFiles.get(secondColumn);
                this.lowerRight = secondRowFiles.get(secondColumn);
            } else {
                this.upperRight = null;
                this.lowerRight = null;
            }
        }

        boolean hasContent() {
            return (upperLeft != null) || (upperRight != null) || (lowerLeft != null) || (lowerRight != null);
        }

        /**
         * Creates a scaled (50%) mipmap from this source.
         *
         * @param  scaledFile  file to save.
         *
         * @throws IOException
         *   if the mipmap cannot be saved.
         */
        void saveScaledFile(final File scaledFile)
                throws IOException {

            final BufferedImage fourTileImage =
                    new BufferedImage(boxWidth * 2, boxHeight * 2, BufferedImage.TYPE_INT_ARGB);
            final Graphics2D fourTileGraphics = fourTileImage.createGraphics();
            drawImage(upperLeft, 0, 0, fourTileGraphics);
            drawImage(upperRight, boxWidth, 0, fourTileGraphics);
            drawImage(lowerLeft, 0, boxHeight, fourTileGraphics);
            drawImage(lowerRight, boxWidth, boxHeight, fourTileGraphics);

            final ImagePlus fourTileImagePlus = new ImagePlus("", fourTileImage);

            final ImageProcessor downSampledImageProcessor =
                    Downsampler.downsampleImageProcessor(fourTileImagePlus.getProcessor());

            saveImage(downSampledImageProcessor.getBufferedImage(), scaledFile, isLabel, format);

            fourTileGraphics.dispose();
        }

        private void drawImage(final File file,
                               final int x,
                               final int y,
                               final Graphics2D fourTileGraphics) {
            if (file != null) {
                final BufferedImage sourceImage = Utils.openImage(file.getAbsolutePath());
                fourTileGraphics.drawImage(sourceImage, x, y, null);
            }
        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(BoxMipmapGenerator.class);
}


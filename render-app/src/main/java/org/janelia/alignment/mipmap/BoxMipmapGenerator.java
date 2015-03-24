package org.janelia.alignment.mipmap;

import ij.ImagePlus;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

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

import org.janelia.alignment.Utils;
import org.janelia.alignment.spec.Bounds;
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
    private final int lastSourceRow;
    private final int lastSourceColumn;

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
     * @param  lastSourceRow     number of row (0-based) containing bottom right tile in layer.
     * @param  lastSourceColumn  number of column (0-based) containing bottom right tile in layer.
     */
    public BoxMipmapGenerator(final int z,
                              final boolean isLabel,
                              final String format,
                              final int boxWidth,
                              final int boxHeight,
                              final File boxDirectory,
                              final int sourceLevel,
                              final int lastSourceRow,
                              final int lastSourceColumn) {
        this.z = z;
        this.isLabel = isLabel;
        this.format = format;
        this.boxWidth = boxWidth;
        this.boxHeight = boxHeight;
        this.boxDirectory = boxDirectory;
        this.sourceLevel = sourceLevel;
        this.lastSourceRow = lastSourceRow;
        this.lastSourceColumn = lastSourceColumn;

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
    public int getSourceLevel() {
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
                                                                              (lastSourceRow / 2),
                                                                              (lastSourceColumn / 2));
        List<File> firstRowFiles;
        int secondRow;
        List<File> secondRowFiles;
        MipmapSource mipmapSource;
        File scaledFile;

        for (int sourceRow = 0; sourceRow <= lastSourceRow; sourceRow += 2) {

            firstRowFiles = rowFileLists.get(sourceRow);

            secondRow = sourceRow + 1;

            if (secondRow <= lastSourceRow) {
                secondRowFiles = rowFileLists.get(secondRow);
            } else {
                secondRowFiles = emptyRow;
            }

            for (int sourceColumn = 0; sourceColumn <= lastSourceColumn; sourceColumn += 2) {

                mipmapSource = new MipmapSource(firstRowFiles,
                                                secondRowFiles,
                                                sourceRow,
                                                sourceColumn,
                                                lastSourceColumn);

                scaledFile = mipmapSource.saveScaledFile(scaledLevel, z);

                nextLevelGenerator.addSource(mipmapSource.scaledRow, mipmapSource.scaledColumn, scaledFile);
            }
        }

        return nextLevelGenerator;
    }

    /**
     * Generates a CATMAID overview image from this generator's source image.
     * An overview image will only be generated if this generator contains one and only one source image.
     *
     * @param  overviewWidth  width of the overview image.
     * @param  layerBounds    full scale (level 0) bounds for tiles in layer (used to clip source image).
     *
     * @return the generated overview image file or null if generation was skipped
     *         because this generator has more than one source image.
     *
     * @throws IOException
     *   if the overview image cannot be saved to disk.
     */
    public File generateOverview(final int overviewWidth,
                                 final Bounds layerBounds)
            throws IOException {

        File overviewFile = null;

        if ((lastSourceRow == 0) && (lastSourceColumn == 0)) {

            double scaledLayerMaxX = layerBounds.getMaxX();
            double scaledLayerMaxY = layerBounds.getMaxX();
            for (int level = 0; level < sourceLevel; level++) {
                scaledLayerMaxX = scaledLayerMaxX / 2;
                scaledLayerMaxY = scaledLayerMaxY / 2;
            }

            LOG.info("generateOverview: generating overview with width {} for z={}, sourceLevel={}, scaledLayerMaxX={}, scaledLayerMaxY={}",
                     overviewWidth, z, sourceLevel, scaledLayerMaxX, scaledLayerMaxY);

            final Path overviewDirPath = Paths.get(boxDirectory.getAbsolutePath(),
                                                   "small");

            overviewFile = setupImageFile(overviewDirPath,
                                          z + "." + format.toLowerCase());

            final List<File> firstRowFiles = rowFileLists.get(0);
            final File sourceFile = firstRowFiles.get(0);
            final BufferedImage sourceImage = Utils.openImage(sourceFile.getAbsolutePath());
            final BufferedImage clippedSourceImage =
                    sourceImage.getSubimage(0, 0, (int) scaledLayerMaxX, (int) scaledLayerMaxY);

            final int overviewHeight = (int)
                    (((double) overviewWidth / clippedSourceImage.getWidth()) * clippedSourceImage.getHeight());

            final BufferedImage overviewImage =
                    new BufferedImage(overviewWidth, overviewHeight, BufferedImage.TYPE_INT_ARGB);

            final Graphics2D overviewGraphics = overviewImage.createGraphics();
            overviewGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            overviewGraphics.drawImage(clippedSourceImage, 0, 0, overviewWidth, overviewHeight, null);
            overviewGraphics.dispose();

            if (isLabel) {
                final BufferedImage labelOverviewImage = BoxMipmapGenerator.convertArgbLabelTo16BitGray(overviewImage);
                Utils.saveImage(labelOverviewImage, overviewFile.getAbsolutePath(), format, false, 0.85f);
            } else {
                Utils.saveImage(overviewImage, overviewFile.getAbsolutePath(), format, true, 0.85f);
            }

        } else {
            LOG.info("generateOverview: skipping generation, z={}, sourceLevel={}, lastSourceRow={}, lastSourceColumn={}",
                     z, sourceLevel, lastSourceRow, lastSourceColumn, z);
        }

        return overviewFile;
    }

    /**
     * Utility to ensure that all parent directories are created for the specified file.
     *
     * @param  imageDirPath   parent directory path.
     * @param  imageFileName  file name.
     *
     * @return file object with specified path and name.
     *
     * @throws IOException
     *   if the file's parent directories cannot be created.
     */
    public static File setupImageFile(final Path imageDirPath,
                                      final String imageFileName)
            throws IOException {

        final File imageFile = new File(imageDirPath.toFile(), imageFileName).getAbsoluteFile();
        final File parentDirectory = imageFile.getParentFile();
        if (! parentDirectory.exists()) {
            if (! parentDirectory.mkdirs()) {
                throw new IOException("failed to create " + parentDirectory.getAbsolutePath());
            }
        }

        return imageFile;
    }

    /**
     * Utility to save an image using the CATMAID directory structure.
     *
     * @param  image         image to save.
     * @param  isLabel       indicates that the image is a label and not a standard image.
     * @param  format        format in which to save the image.
     * @param  boxDirectory  root directory for the image (e.g. /project/stack/width-x-height)
     * @param  level         scale level for the image.
     * @param  z             z value for layer containing the image.
     * @param  row           row containing image.
     * @param  column        column containing image.
     *
     * @return the file that was saved for the image.
     *
     * @throws IOException
     *   if the image cannot be saved for any reason.
     */
    public static File saveImage(final BufferedImage image,
                                 final boolean isLabel,
                                 final String format,
                                 final File boxDirectory,
                                 final int level,
                                 final int z,
                                 final int row,
                                 final int column)
            throws IOException {

        final Path imageDirPath = Paths.get(boxDirectory.getAbsolutePath(),
                                            String.valueOf(level),
                                            String.valueOf(z),
                                            String.valueOf(row));

        final File imageFile = setupImageFile(imageDirPath,
                                              column + "." + format.toLowerCase());

        if (isLabel) {
            final BufferedImage labelImage = convertArgbLabelTo16BitGray(image);
            Utils.saveImage(labelImage, imageFile.getAbsolutePath(), format, false, 0.85f);
        } else {
            Utils.saveImage(image, imageFile.getAbsolutePath(), format, true, 0.85f);
        }

        return imageFile;
    }

    /**
     * Converts the specified ARGB label image to a 16-bit gray image.
     * Only uses the two lowest order RGB bytes for each pixel (the green and blue values)
     * to calculate the pixel's corresponding 16-bit gray value.
     *
     * @param  image  ARGB image to convert.
     *
     * @return a 16-bit gray image.
     */
    public static BufferedImage convertArgbLabelTo16BitGray(final BufferedImage image) {

        final long startTime = System.currentTimeMillis();

        final int width = image.getWidth();
        final int height = image.getHeight();

        int p = 0;
        final short[] convertedPixels = new short[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                convertedPixels[p] = (short) image.getRGB(x, y);
                p++;
            }
        }

        final ShortProcessor sp = new ShortProcessor(width, height);
        sp.setPixels(convertedPixels);

        final long elapsedTime = System.currentTimeMillis() - startTime;
        LOG.debug("convertArgbLabelTo16BitGray: converted {} pixels in {} milliseconds", convertedPixels.length, elapsedTime);

        return sp.get16BitBufferedImage();
    }

    /**
     * Container for the (up to) 4 source files used to generate a down-sampled mipmap.
     */
    private class MipmapSource {

        private File upperLeft;
        private File upperRight;
        private File lowerLeft;
        private File lowerRight;

        private int scaledRow;
        private int scaledColumn;

        /**
         *
         * @param  firstRowFiles     first row of source files for this mipmap.
         * @param  secondRowFiles    second row of source files for this mipmap.
         * @param  sourceRow         row index for the upper left tile.
         * @param  sourceColumn      column index for the upper left tile.
         * @param  lastSourceColumn  index of the largest column with tiles.
         */
        public MipmapSource(final List<File> firstRowFiles,
                            final List<File> secondRowFiles,
                            final int sourceRow,
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

            this.scaledRow = (sourceRow / 2);
            this.scaledColumn = (sourceColumn / 2);
        }

        /**
         * Creates a scaled (50%) mipmap from this source.
         *
         * @param  scaledLevel  scale level for the image being generated (source level + 1).
         * @param  z            z value for layer that contains the source tiles.
         *
         * @return the file for the generated mipmap image.
         *
         * @throws IOException
         *   if the mipmap cannot be saved.
         */
        public File saveScaledFile(final int scaledLevel,
                                   final int z)
                throws IOException {

            final File scaledFile;
            if ((upperLeft != null) || (upperRight != null) || (lowerLeft != null) || (lowerRight != null)) {

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

                scaledFile = saveImage(downSampledImageProcessor.getBufferedImage(),
                                       isLabel,
                                       format,
                                       boxDirectory,
                                       scaledLevel,
                                       z,
                                       scaledRow,
                                       scaledColumn);

                fourTileGraphics.dispose();
            } else {
                scaledFile = null;
            }

            return scaledFile;
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


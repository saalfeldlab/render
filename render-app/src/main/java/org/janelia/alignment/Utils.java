package org.janelia.alignment;

import ij.ImagePlus;
import ij.io.FileInfo;
import ij.io.FileSaver;
import ij.io.Opener;
import ij.io.TiffEncoder;
import ij.measure.Calibration;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;
import javax.imageio.stream.ImageOutputStream;

import mpicbg.models.AffineModel2D;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.SimilarityModel2D;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Stephan Saalfeld <saalfeld@janelia.hhmi.org>
 */
public class Utils {

    public static final String JPEG_FORMAT = "jpg";
    public static final String PNG_FORMAT = "png";
    public static final String TIFF_FORMAT = "tiff";
    public static final String TIF_FORMAT = "tif";
    public static final String RAW_FORMAT = "raw";

    private static final Logger LOG = LoggerFactory.getLogger(Utils.class);

    private Utils() {
    }

//    final static private double LOG2 = Math.log(2.0);

    /**
     * Writes the specified image using ImageIO.
     */
    public static void writeImage(final BufferedImage image,
                                  final String format,
                                  final boolean convertToGray,
                                  final float quality,
                                  final ImageOutputStream outputStream)
            throws IOException {

        // As part of the JPG save fix, the signature of this method was changed to accept
        // BufferedImage instead of RenderedImage.  A better solution would be highly appreciated.

        final Iterator<ImageWriter> writersForFormat = ImageIO.getImageWritersByFormatName(format);

        if ((writersForFormat != null) && writersForFormat.hasNext()) {
            final ImageWriter writer = writersForFormat.next();
            try {
                writer.setOutput(outputStream);

                // TODO: make gray scale default if there is no need for RGB jpegs
                BufferedImage convertedImage = image;
                if (convertToGray) {
                    convertedImage = new BufferedImage(image.getWidth(),
                                                       image.getHeight(),
                                                       BufferedImage.TYPE_BYTE_GRAY);
                    final Graphics2D g2d = convertedImage.createGraphics();
                    g2d.drawImage(image, 0, 0, null);
                    g2d.dispose();
                }

                if (format.equalsIgnoreCase(JPEG_FORMAT)) {
                    final ImageWriteParam param = writer.getDefaultWriteParam();
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(quality);

                    if (! convertToGray) {
                        // Fixed JPG saving through converting INT_ARGB to INT_RGB.
                        // Previously, JPGs ended up being saved as four channel CMYKs.
                        // Now, conversion goes through drawing the INT_ARGB image
                        // into an INT_RGB image which feels wasteful.
                        convertedImage = new BufferedImage(image.getWidth(),
                                                           image.getHeight(),
                                                           BufferedImage.TYPE_INT_RGB);
                        final Graphics2D g2d = convertedImage.createGraphics();
                        g2d.drawImage(image, 0, 0, null);
                        g2d.dispose();
                    }

                    writer.write(null, new IIOImage(convertedImage, null, null), param);

                } else {
                    writer.write(convertedImage);
                }
            } finally {
                if (writer != null) {
                    writer.dispose();
                }
            }
        } else {
            throw new IOException("no ImageIO writers exist for the '" + format + "' format");
        }
    }

    /**
     * Writes {@link FileInfo} to the specified {@link OutputStream} using ImageJ's {@link TiffEncoder}.
     *
     * @param  fileInfo          file information (including pixels).
     * @param  outputStream      target stream.
     *
     * @throws IOException
     *   if any errors occur.
     */
    public static void writeTiffImage(final FileInfo fileInfo,
                                      final OutputStream outputStream)
            throws IOException {
        final TiffEncoder tiffEncoder = new TiffEncoder(fileInfo);
        tiffEncoder.write(outputStream);
    }

    /**
     * Writes a {@link BufferedImage} to the specified {@link OutputStream} using ImageJ's {@link TiffEncoder}.
     *
     * @param  bufferedImage     image to write.
     * @param  outputStream      target stream.
     *
     * @throws IOException
     *   if any errors occur.
     */
    public static void writeTiffImage(final BufferedImage bufferedImage,
                                      final OutputStream outputStream)
            throws IOException {
        final ImagePlus ip = new ImagePlus("", bufferedImage);
        writeTiffImage(ip.getFileInfo(), outputStream);
    }

    public static File prepareFileForWrite(final String pathOrUriString) {
        final File file = new File(convertPathOrUriStringToUri(pathOrUriString));

        final File parentDirectory = file.getParentFile();
        if ((parentDirectory != null) && (!parentDirectory.exists())) {
            if (!parentDirectory.mkdirs()) {
                // check for existence again in case another parallel process already created the directory
                if (! parentDirectory.exists()) {
                    throw new IllegalArgumentException("failed to create directory " +
                                                       parentDirectory.getAbsolutePath());
                }
            }
        }

        return file;
    }

    /**
     * Saves the specified image along with resolution metadata to a file using ImageJ's {@link TiffEncoder}.
     *
     * @param  bufferedImage     image to write.
     * @param  resolutionValues  full scale resolution values for stack.
     * @param  resolutionUnit    resolution unit (e.g. nm)
     * @param  renderScale       scale of the rendered image.
     * @param  pathOrUriString   path for output file to be written.
     */
    public static void saveTiffImageWithResolution(final BufferedImage bufferedImage,
                                                   final List<Double> resolutionValues,
                                                   final String resolutionUnit,
                                                   final double renderScale,
                                                   final String pathOrUriString)
            throws IOException {


        final File file = prepareFileForWrite(pathOrUriString);

        final ImagePlus ip = new ImagePlus("", bufferedImage);

        if ((resolutionValues != null) && (resolutionUnit != null)) {
            final Calibration calibration = new Calibration(ip);
            if (resolutionValues.size() > 1) {
                calibration.pixelWidth = resolutionValues.get(0) / renderScale;
                calibration.pixelHeight = resolutionValues.get(1) / renderScale;
            }
            if (resolutionValues.size() > 2) {
                calibration.pixelDepth = resolutionValues.get(2) / renderScale;
            }
            calibration.setUnit(resolutionUnit);
            ip.setCalibration(calibration);
        } else {
            LOG.warn("saveTiffImageWithResolution: missing resolution data, resolutionValues={}, resolutionUnit={}",
                     resolutionValues, resolutionUnit);
        }

        final FileInfo fileInfo = ip.getFileInfo();
        final FileSaver fileSaver = new FileSaver(ip);
        fileInfo.description = fileSaver.getDescriptionString();
        
        try (final FileOutputStream outputStream = new FileOutputStream(file)) {
            writeTiffImage(fileInfo, outputStream);
        }

        LOG.info("saveTiffImageWithResolution: exit, saved {}", file.getAbsolutePath());
    }

    /**
     * Saves the specified image to a file using ImageIO.
     */
    public static void saveImage(final BufferedImage image,
                                 final String pathOrUriString,
                                 final String format,
                                 final boolean convertToGray,
                                 final float quality)
            throws IOException {

        final File file = prepareFileForWrite(pathOrUriString);

        if (TIFF_FORMAT.equals(format) || (TIF_FORMAT.equals(format))) {

            try (final FileOutputStream outputStream = new FileOutputStream(file)) {
                writeTiffImage(image, outputStream);
            }

        } else {

            try (final FileImageOutputStream outputStream = new FileImageOutputStream(file)) {
                writeImage(image, format, convertToGray, quality, outputStream);
            }

        }

        LOG.info("saveImage: exit, saved {}", file.getAbsolutePath());
    }

    public static void saveImage(final BufferedImage image,
                                 final File toFile,
                                 final boolean convertToGray,
                                 final float quality)
            throws IOException {

        final String fileAbsolutePath = toFile.getAbsolutePath();
        final String outputFormat = fileAbsolutePath.substring(fileAbsolutePath.lastIndexOf('.') + 1);
        saveImage(image, fileAbsolutePath, outputFormat, convertToGray, quality);
    }

    /**
     * Open an ImagePlus from a file.
     */
    public static ImagePlus openImagePlus(final String pathString) {
        final Opener opener = new Opener();
        return opener.openImage(pathString);
    }

    /**
     * Open an Image from a file.  Try ImageIO first, then ImageJ.
     */
    public static BufferedImage openImage(final String path) {
        BufferedImage image = null;
        try {
            final File file = new File(path);
            if (file.exists()) {
                final BufferedImage jpg = ImageIO.read(file);

                // This gymnastic is necessary to get reproducible gray
                // values, just opening a JPG or PNG, even when saved by
                // ImageIO, and grabbing its pixels results in gray values
                // with a non-matching gamma transfer function, I cannot tell
                // why...
                image = new BufferedImage(jpg.getWidth(), jpg.getHeight(), BufferedImage.TYPE_INT_ARGB);
                image.createGraphics().drawImage(jpg, 0, 0, null);
            }
        } catch (final Exception e) {
            try {
                final ImagePlus imp = openImagePlus(path);
                if (imp != null) {
                    image = imp.getBufferedImage();
                } else {
                    image = null;
                }
            } catch (final Exception f) {
                image = null;
            }
        }
        return image;
    }


//    /**
//     * Combine a 0x??rgb int[] raster and an unsigned byte[] alpha channel into a 0xargb int[] raster.  The operation is
//     * perfomed in place on the int[] raster.
//     */
//    public static void combineARGB(final int[] rgb,
//                                         final byte[] a) {
//        for (int i = 0; i < rgb.length; ++i) {
//            rgb[i] &= 0x00ffffff;
//            rgb[i] |= (a[i] & 0xff) << 24;
//        }
//    }


    /**
     * Sample the average scaling of a given {@link CoordinateTransform} by transferring a set of point samples using
     * the {@link CoordinateTransform} and then least-squares fitting a {@link SimilarityModel2D} to it.
     *
     * @param width  of the samples set
     * @param height of the samples set
     * @param dx     spacing between samples
     *
     * @return average scale factor
     */
    public static double sampleAverageScale(final CoordinateTransform ct,
                                            final int width,
                                            final int height,
                                            final double dx) {
        final ArrayList<PointMatch> samples = new ArrayList<>();
        for (double y = 0; y < height; y += dx) {
            for (double x = 0; x < width; x += dx) {
                final Point p = new Point(new double[]{x, y});
                p.apply(ct);
                samples.add(new PointMatch(p, p));
            }
        }
        final AffineModel2D model = new AffineModel2D();
        try {
            model.fit(samples);
        } catch (final NotEnoughDataPointsException | IllDefinedDataPointsException e) {
            LOG.warn("failed to fit samples, returning scale factor of 1", e);
            return 1;
        }
        final double[] data = new double[6];
        model.toArray(data);
        // return 1;
        return Math.sqrt(Math.max(data[0] * data[0] + data[1] * data[1], data[2] * data[2] + data[3] * data[3]));
    }


    public static int bestMipmapLevel(final double scale) {
        int invScale = (int) (1.0 / scale);
        int scaleLevel = 0;
        while (invScale > 1) {
            invScale >>= 1;
            ++scaleLevel;
        }
        return scaleLevel;
    }


//    /**
//     * Returns the exact fractional `index' of the desired scale in a power of 2 mipmap pyramid.
//     */
//    public static double mipmapLevel(final double scale) {
//        return Math.log(1.0 / scale) / LOG2;
//    }


    /**
     * Create an affine transformation that compensates for both scale and pixel shift of a mipmap level that was
     * generated by top-left pixel averaging.
     */
    public static AffineModel2D createScaleLevelTransform(final int scaleLevel) {
        final AffineModel2D a = new AffineModel2D();
        final int scale = 1 << scaleLevel;
        final double t = (scale - 1) * 0.5;
        a.set(scale, 0, 0, scale, t, t);
        return a;
    }

//    /**
//     * Create an affine transformation that compensates for both scale and pixel shift of a mipmap level that was
//     * generated by top-left pixel averaging.
//     */
//    public static AffineModel2D createScaleLevelTransform(final double scaleLevel) {
//        final AffineModel2D a = new AffineModel2D();
//        final double scale = Math.pow(2, scaleLevel);
//        final double t = (scale - 1) * 0.5;
//        a.set(scale, 0, 0, scale, t, t);
//        return a;
//    }

    public static URI convertPathOrUriStringToUri(final String pathOrUriString)
            throws IllegalArgumentException {
        final URI uri;
        if (pathOrUriString.indexOf(':') == -1) {
            // convert relative path to fully qualified URL
            File file = new File(pathOrUriString);
            try {
                file = file.getCanonicalFile();
                uri = file.toURI();
            } catch (final Throwable t) {
                throw new IllegalArgumentException("failed to convert '" + pathOrUriString + "' to a URI", t);
            }
        } else {
            try {
                uri = new URI(pathOrUriString);
            } catch (final Throwable t) {
                throw new IllegalArgumentException("failed to create URI for '" + pathOrUriString + "'", t);
            }
        }
        return uri;
    }

    public static final Comparator<String> NULLS_FIRST_STRING_COMPARATOR =
            Comparator.nullsFirst(Comparator.naturalOrder());

}

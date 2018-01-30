package org.janelia.render.service.util;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.DataBufferUShort;
import java.awt.image.SinglePixelPackedSampleModel;
import java.io.IOException;
import java.io.OutputStream;

import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.StreamingOutput;

import org.janelia.alignment.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ar.com.hjg.pngj.FilterType;
import ar.com.hjg.pngj.ImageInfo;
import ar.com.hjg.pngj.ImageLineHelper;
import ar.com.hjg.pngj.ImageLineInt;
import ar.com.hjg.pngj.PngWriter;

/**
 * Wrapper for {@link java.awt.image.BufferedImage} instances that need to be
 * streamed as the response for a JAX-RS API request.
 * Uses {@link org.janelia.alignment.Utils#writeImage} to do the real work.
 *
 * @author Eric Trautman
 */
public class BufferedImageStreamingOutput implements StreamingOutput {

    private final BufferedImage targetImage;
    private final String format;
    private final boolean convertToGray;
    private final float quality;

    public BufferedImageStreamingOutput(final BufferedImage targetImage,
                                        final String format,
                                        final boolean convertToGray,
                                        final float quality) {
        this.targetImage = targetImage;
        this.format = format;
        this.convertToGray = convertToGray;
        this.quality = quality;
    }

    @Override
    public void write(final OutputStream outputStream)
            throws IOException, WebApplicationException {

        LOG.info("write: entry");

        if (Utils.PNG_FORMAT.equals(format)) {
            writePngImage(targetImage, 6, FilterType.FILTER_PAETH, outputStream);
        } else if (Utils.TIFF_FORMAT.equals(format)) {
            Utils.writeTiffImage(targetImage, outputStream);
        } else {
            final ImageOutputStream imageOutputStream = new MemoryCacheImageOutputStream(outputStream);
            Utils.writeImage(targetImage, format, convertToGray, quality, imageOutputStream);
        }

        LOG.info("write: exit");
    }

    /**
     * Writes a {@link BufferedImage} to the specified {@link OutputStream} using the PNGJ library
     * which is much faster than Java's ImageIO library.
     *
     * This implementation was copied from
     * <a href="https://github.com/leonbloy/pngj/wiki/Snippets">
     *     https://github.com/leonbloy/pngj/wiki/Snippets
     * </a>.
     *
     * @param  bufferedImage     image to write.
     * @param  compressionLevel  0 (no compression) - 9 (max compression)
     * @param  filterType        internal prediction filter type.
     * @param  outputStream      target stream.
     *
     * @throws IOException
     *   if the image is not ARGB or it's data buffer contains the wrong number of banks.
     */
    public static void writePngImage(final BufferedImage bufferedImage,
                                     final int compressionLevel,
                                     final FilterType filterType,
                                     final OutputStream outputStream)
            throws IOException {



        if (bufferedImage.getType() == BufferedImage.TYPE_INT_ARGB) {
            // Existing code for TYPE_INT_ARGB
            final ImageInfo imageInfo = new ImageInfo(bufferedImage.getWidth(), bufferedImage.getHeight(), 8, true);

            final PngWriter pngWriter = new PngWriter(outputStream, imageInfo);
            pngWriter.setCompLevel(compressionLevel);
            pngWriter.setFilterType(filterType);

            final DataBufferInt dataBuffer =((DataBufferInt) bufferedImage.getRaster().getDataBuffer());
            if (dataBuffer.getNumBanks() != 1) {
                throw new IOException("invalid number of banks (" + dataBuffer.getNumBanks() + "), must be 1");
            }

            final SinglePixelPackedSampleModel sampleModel = (SinglePixelPackedSampleModel) bufferedImage.getSampleModel();
            final ImageLineInt line = new ImageLineInt(imageInfo);
            final int[] data = dataBuffer.getData();
            for (int row = 0; row < imageInfo.rows; row++) {
                int elem = sampleModel.getOffset(0, row);
                for (int col = 0; col < imageInfo.cols; col++) {
                    final int sample = data[elem++];
                    ImageLineHelper.setPixelRGBA8(line, col, sample);
                }
                pngWriter.writeRow(line, row);
            }
            pngWriter.end();

        } else if (bufferedImage.getType() == BufferedImage.TYPE_USHORT_GRAY) {
            // Modified code to avoid "java.awt.image.DataBufferUShort cannot be cast to java.awt.image.DataBufferInt"
            final ImageInfo imageInfo = new ImageInfo(bufferedImage.getWidth(), bufferedImage.getHeight(), 16, false, true, false);
            final PngWriter pngWriter = new PngWriter(outputStream, imageInfo);
            pngWriter.setCompLevel(compressionLevel);
            pngWriter.setFilterType(filterType);

            final DataBufferUShort dataBuffer = (DataBufferUShort) bufferedImage.getRaster().getDataBuffer();
            if (dataBuffer.getNumBanks() != 1) {
                throw new IOException("invalid number of banks (" + dataBuffer.getNumBanks() + "), must be 1");
            }

            final int [] scanline = new int[imageInfo.cols];
            ImageLineInt line = new ImageLineInt(imageInfo, scanline);

            for (int row = 0; row < imageInfo.rows; row++) {
                for (int col = 0; col < imageInfo.cols; col++) {
                    scanline[col] = dataBuffer.getData()[row * imageInfo.cols + col];
                }
                pngWriter.writeRow(line, row);
            }
            pngWriter.end();
        } else {
            throw new IOException("invalid image type (" + bufferedImage.getType() +
                    "), must be BufferedImage.TYPE_INT_ARGB or BufferedImage.TYPE_USHORT_GRAY");
        }

//        // This looked like a nicer option, but only works for DataBufferByte (not DataBufferInt)
//        final ImageLineSetARGBbi lines = new ImageLineSetARGBbi(bufferedImage, imageInfo);
//        pngWriter.writeRows(lines);
//        pngWriter.end();
    }

    private static final Logger LOG = LoggerFactory.getLogger(BufferedImageStreamingOutput.class);

}

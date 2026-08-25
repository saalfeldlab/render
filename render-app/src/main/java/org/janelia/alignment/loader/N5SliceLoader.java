package org.janelia.alignment.loader;

import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.Charset;

import net.imglib2.loops.LoopBuilder;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.integer.ShortType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

/**
 * Loads a 2D slice of an N5 volume identified as:
 * <pre>
 *     file://[n5BasePath]?dataSet=[dataSet]&x=[x]&y=[y]&z=[z]&w=[width]&h=[height]
 *
 *     Example:
 *       file:///nrs/flyem/tmp/VNC-align.n5?dataSet=/align/slab-26/raw/s0&x=512&y=640&z=1656&w=384&h=640
 * </pre>
 *
 * @author Eric Trautman
 */
public class N5SliceLoader implements ImageLoader {

    /** Shareable instance of this loader. */
    public static final N5SliceLoader INSTANCE = new N5SliceLoader();

    @Override
    public boolean hasSame3DContext(final ImageLoader otherLoader) {
        return otherLoader instanceof N5SliceLoader;
    }

    @Override
    public ImageProcessor load(final String urlString)
            throws IllegalArgumentException {

        final ImageProcessor imageProcessor;

        try {

            // "file://<n5BasePath>?dataSet=<dataSet>&x=<x>&y=<y>&z=<z>&w=<width>&h=<height>

            final URI uri = new URI(urlString);
            final String scheme = uri.getScheme();

            // TODO: remove file scheme restriction once remote URL friendly N5Reader is implemented
            if ((scheme != null) && (! scheme.equals("file"))) {
                throw new IllegalArgumentException(scheme + " scheme not currently supported, must be a local file");
            }

            final String defaultCharsetName = Charset.defaultCharset().name();
            final String basePath = URLDecoder.decode(uri.getPath(), defaultCharsetName);
            final String query = uri.getQuery();
            final String[] queryKeyValuePairs = query.split("&"); // note: uses "fastpath" for simple regex
            String dataSet = null;
            Long x = null;
            Long y = null;
            Long z = null;
            Integer width = null;
            Integer height = null;
            for (final String keyValuePair : queryKeyValuePairs) {
                final String[] keyValue = keyValuePair.split("=");
                if (keyValue.length == 2) {
                    final String key = keyValue[0];
                    if ("x".equals(key)){
                        x = Long.valueOf(keyValue[1]);
                    } else if ("y".equals(key)) {
                        y = Long.valueOf(keyValue[1]);
                    } else if ("z".equals(key)) {
                        z = Long.valueOf(keyValue[1]);
                    } else if ("w".equals(key)) {
                        width = Integer.valueOf(keyValue[1]);
                    } else if ("h".equals(key)) {
                        height = Integer.valueOf(keyValue[1]);
                    } else if ("dataSet".equals(key)) {
                        dataSet = URLDecoder.decode(keyValue[1], defaultCharsetName);
                    }
                }
            }

            long[] xAndYOffsets = null;
            if (x != null) {
                if (y != null) {
                    xAndYOffsets = new long[] { x, y };
                } else {
                    xAndYOffsets = new long[] { x, 0 };
                }
            } else if (y != null) {
                xAndYOffsets = new long[] { 0, y };
            }

            if ((basePath != null) && (dataSet != null)) {

                // TODO: review load process (seems very slow)

                final N5Reader reader = buildReader(basePath);
                final DatasetAttributes datasetAttributes = reader.getDatasetAttributes(dataSet);

                if (datasetAttributes == null) {
                    throw new IllegalArgumentException("attributes not found for dataset '" + dataSet + "' in '" + urlString + "'");
                }

                final DataType dataType = datasetAttributes.getDataType();
                final long[] dimensions = datasetAttributes.getDimensions();

                if (width == null) {
                    width = (int) dimensions[0];
                }

                if (height == null) {
                    height = (int) dimensions[1];
                }

                imageProcessor = switch (dataType) {
                    case UINT8 -> UNSIGNED_BYTE_HELPER.load(reader, dataSet, width, height, xAndYOffsets, z);
                    case INT16 -> SHORT_HELPER.load(reader, dataSet, width, height, xAndYOffsets, z);
                    case FLOAT32 -> FLOAT_HELPER.load(reader, dataSet, width, height, xAndYOffsets, z);
                    // This covers INT8, INT32, INT64, FLOAT64, OBJECT, UINT16, UINT32, UINT64
                    default -> throw new IllegalArgumentException("dataType " + dataType + " is not supported");
                };

            } else {
                throw new IllegalArgumentException(
                        "n5 url '" + urlString +
                        "' is missing basePath and/or dataSet, pattern should be " +
                        "file://<n5BasePath>?dataSet=<dataSet>&x=<x>&y=<y>&z=<z>&w=<width>&h=<height>");
            }

        } catch (final Throwable t) {
            throw new IllegalArgumentException("failed to load n5 slice '" + urlString + "'", t);
        }

        return imageProcessor;
    }

    public N5Reader buildReader(final String basePath)
            throws IOException {
        return new N5FSReader(basePath);
    }

    public static abstract class Helper <A extends NativeType< A >, B extends ImageProcessor> {

        public abstract B buildImageProcessor(final int width,
                                              final int height);

        public abstract RandomAccessibleInterval<A> setupTarget(B forImageProcessor);

        /**
         * Hook to convert pixel values if necessary. Default is to return the pixel as is.
         * @param pixel the pixel to convert
         * @return the converted pixel (might be the same instance as the input)
         */
        protected A convert(final A pixel) {
            return pixel;
        }

        public ImageProcessor load(final N5Reader reader,
                                   final String dataSet,
                                   final int width,
                                   final int height,
                                   final long[] xAndYOffsets,
                                   final Long zOffset)
                throws IOException {

            final B imageProcessor = buildImageProcessor(width, height);
            final RandomAccessibleInterval<A> target = setupTarget(imageProcessor);
            final RandomAccessibleInterval<A> source = N5Utils.open(reader, dataSet);

            RandomAccessibleInterval<A> slice = zOffset == null ? source : Views.hyperSlice(source, 2, zOffset);
            if (xAndYOffsets != null) {
                slice = Views.offsetInterval(slice, xAndYOffsets, new long[] {0,1});
            }

            LoopBuilder.setImages(slice, target).forEachPixel((s, t) -> t.set(convert(s)));

            return imageProcessor;
        }
    }

    public static Helper<UnsignedByteType, ByteProcessor> UNSIGNED_BYTE_HELPER =
            new Helper<>() {
                @Override
                public ByteProcessor buildImageProcessor(final int width,
                                                         final int height) {
                    return new ByteProcessor(width, height);
                }

                @Override
                public RandomAccessibleInterval<UnsignedByteType> setupTarget(final ByteProcessor forImageProcessor) {
                    return ArrayImgs.unsignedBytes(
                            (byte[]) forImageProcessor.getPixels(),
                            forImageProcessor.getWidth(),
                            forImageProcessor.getHeight());
                }
            };

    public static Helper<ShortType, ShortProcessor> SHORT_HELPER =
            new Helper<>() {
                @Override
                public ShortProcessor buildImageProcessor(final int width,
                                                          final int height) {
                    return new ShortProcessor(width, height);
                }

                @Override
                public RandomAccessibleInterval<ShortType> setupTarget(final ShortProcessor forImageProcessor) {
                    return ArrayImgs.shorts(
                            (short[]) forImageProcessor.getPixels(),
                            forImageProcessor.getWidth(),
                            forImageProcessor.getHeight());
                }

                @Override
                // Since all 16bit HDF5 slices we open are converted .dat files, we need to properly convert the pixel values
                protected ShortType convert(final ShortType pixel) {
                    pixel.set(UnsignedShortType.getCodedSignedShortChecked(32768 - pixel.get()));
                    return pixel;
                }
            };

    public static Helper<FloatType, FloatProcessor> FLOAT_HELPER =
            new Helper<>() {
                @Override
                public FloatProcessor buildImageProcessor(final int width,
                                                          final int height) {
                    return new FloatProcessor(width, height);
                }

                @Override
                public RandomAccessibleInterval<FloatType> setupTarget(final FloatProcessor forImageProcessor) {
                    return ArrayImgs.floats(
                            (float[]) forImageProcessor.getPixels(),
                            forImageProcessor.getWidth(),
                            forImageProcessor.getHeight());
                }
            };
}

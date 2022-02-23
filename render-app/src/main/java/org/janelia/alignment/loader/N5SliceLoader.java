package org.janelia.alignment.loader;

import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import net.imglib2.IterableInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.integer.ShortType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

/**
 * Loads a 2D slice of an n5 volume identified as:
 * <pre>
 *     n5://[n5BasePath]?dataSet=[dataSet]&x=[x]&y=[y]&z=[z]&w=[width]&h=[height]
 *
 *     Example:
 *       n5:///nrs/flyem/tmp/VNC-align.n5?dataSet=/align/slab-26/raw/s0&x=512&y=640&z=1656&w=384&h=640
 * </pre>
 *
 * NOTE: A current limitation is that URLs need to exactly match the pattern above
 * (they are not parsed as true URLs where positioning of parameters does not matter).
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
            final Matcher m = N5_SLICE_URL_PATTERN.matcher(urlString);

            if (m.matches()) {

                // TODO: confirm encoding location info in single URL (with "n5" protocol) is okay

                // "n5://<n5BasePath>?dataSet=<dataSet>&x=<x>&y=<y>&z=<z>&w=<width>&h=<height>
                final String basePath = m.group(1);
                final String dataSet = m.group(2);
                final long x = Long.parseLong(m.group(3));
                final long y = Long.parseLong(m.group(4));
                final long z = Long.parseLong(m.group(5));
                final long[] xAndYOffsets = new long[] { x, y };
                final int width = Integer.parseInt(m.group(6));
                final int height = Integer.parseInt(m.group(7));

                // TODO: review load process (seems very slow)

                final N5Reader reader = new N5FSReader(basePath);
                final DatasetAttributes datasetAttributes = reader.getDatasetAttributes(dataSet);
                final DataType dataType = datasetAttributes.getDataType();

                switch(dataType) {
                    case UINT8:
                        imageProcessor = UNSIGNED_BYTE_HELPER.load(reader, dataSet, width, height, xAndYOffsets, z);
                        break;
                    case INT16:
                        imageProcessor = SHORT_HELPER.load(reader, dataSet, width, height, xAndYOffsets, z);
                        break;
                    case FLOAT32:
                        imageProcessor = FLOAT_HELPER.load(reader, dataSet, width, height, xAndYOffsets, z);
                        break;
                    default:
                        // case INT8: case INT32: case INT64: case FLOAT64: case OBJECT: case UINT16: case UINT32: case UINT64:
                        throw new IllegalArgumentException("dataType " + dataType + " is not supported");
                }

                // final File debugFile = new File("/Users/trautmane/Desktop/test." + x + "." + y + ".jpg");
                // Utils.saveImage(imageProcessor.getBufferedImage(), debugFile, false, 0.85f);

            } else {
                throw new IllegalArgumentException("n5 url '" + urlString +
                                                   "' does not match pattern " + N5_SLICE_URL_PATTERN_STRING);
            }

        } catch (final Throwable t) {
            throw new IllegalArgumentException("failed to load n5 slice '" + urlString + "'", t);
        }

        return imageProcessor;
    }

    public static abstract class Helper <A extends NativeType< A >, B extends ImageProcessor> {

        public abstract B buildImageProcessor(final int width,
                                              final int height);

        public abstract RandomAccessibleInterval<A> setupTarget(B forImageProcessor);

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
            final IntervalView<Pair<A, A>> pairView =  Views.interval(Views.pair(slice, target),
                                                                      target);
            final IterableInterval<Pair<A, A>> pairs = Views.flatIterable(pairView);

            pairs.forEach(pair -> {
                final A fromPixel = pair.getA();
                final A toPixel = pair.getB();
                toPixel.set(fromPixel);
            });

            return imageProcessor;
        }
    }

    public static Helper<UnsignedByteType, ByteProcessor> UNSIGNED_BYTE_HELPER =
            new Helper<UnsignedByteType, ByteProcessor>() {
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
            new Helper<ShortType, ShortProcessor>() {
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
            };

    public static Helper<FloatType, FloatProcessor> FLOAT_HELPER =
            new Helper<FloatType, FloatProcessor>() {
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


    // "n5://<n5BasePath>?dataSet=<dataSet>&x=<x>&y=<y>&z=<z>&w=<width>&h=<height>
    private static final String N5_SLICE_URL_PATTERN_STRING =
            "n5://([^?]++)\\?dataSet=([^&]++)&x=(\\d++)&y=(\\d++)&z=(\\d++)&w=(\\d++)&h=(\\d++)";

    private static final Pattern N5_SLICE_URL_PATTERN = Pattern.compile(N5_SLICE_URL_PATTERN_STRING);

}

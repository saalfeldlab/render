package org.janelia.alignment.loader;

import ij.process.ImageProcessor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import net.imglib2.IterableInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.NativeType;
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
public abstract class N5SliceLoader<A extends NativeType< A >, B extends ImageProcessor>
        implements ImageLoader {

    public abstract B buildImageProcessor(final int width,
                                          final int height);

    public abstract RandomAccessibleInterval<A> setupTarget(B forImageProcessor);

    @Override
    public boolean hasSame3DContext(@Nonnull final ImageLoader otherLoader) {
        return otherLoader instanceof N5SliceLoader;
    }

    @Override
    public ImageProcessor load(@Nonnull final String urlString)
            throws IllegalArgumentException {

        final B imageProcessor;

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
                final int width = Integer.parseInt(m.group(6));
                final int height = Integer.parseInt(m.group(7));

                // TODO: review load process (seems very slow)

                final N5Reader reader = new N5FSReader(basePath);

                imageProcessor = buildImageProcessor(width, height);

                final RandomAccessibleInterval<A> target = setupTarget(imageProcessor);

                final RandomAccessibleInterval<A> source = N5Utils.open(reader, dataSet);
                final IntervalView<A> layer = Views.hyperSlice(source, 2, z);
                final IntervalView<A> layerWithOffset = Views.offsetInterval(layer, new long[] {x,y}, new long[] {0,1});

                final IntervalView<Pair<A, A>> pairView =  Views.interval(Views.pair(layerWithOffset, target),
                                                                          target);
                final IterableInterval<Pair<A, A>> pairs = Views.flatIterable(pairView);

                pairs.forEach(pair -> {
                    final A fromPixel = pair.getA();
                    final A toPixel = pair.getB();
                    toPixel.set(fromPixel);
                });

//                final File debugFile = new File("/Users/trautmane/Desktop/test." + x + "." + y + ".jpg");
//                Utils.saveImage(imageProcessor.getBufferedImage(), debugFile, false, 0.85f);

            } else {
                throw new IllegalArgumentException("n5 url '" + urlString +
                                                   "' does not match pattern " + N5_SLICE_URL_PATTERN_STRING);
            }

        } catch (final Throwable t) {
            throw new IllegalArgumentException("failed to load n5 slice '" + urlString + "'", t);
        }

        return imageProcessor;
    }

    // "n5://<n5BasePath>?dataSet=<dataSet>&x=<x>&y=<y>&z=<z>&w=<width>&h=<height>
    private static final String N5_SLICE_URL_PATTERN_STRING =
            "n5://([^?]++)\\?dataSet=([^&]++)&x=(\\d++)&y=(\\d++)&z=(\\d++)&w=(\\d++)&h=(\\d++)";

    private static final Pattern N5_SLICE_URL_PATTERN = Pattern.compile(N5_SLICE_URL_PATTERN_STRING);

}

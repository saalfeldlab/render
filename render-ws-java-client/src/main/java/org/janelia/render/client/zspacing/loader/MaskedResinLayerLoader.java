package org.janelia.render.client.zspacing.loader;

import java.util.Arrays;
import java.util.List;

import org.janelia.alignment.util.ImageProcessorCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ij.process.FloatProcessor;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.StopWatch;
import net.imglib2.view.Views;

/**
 * Loads layer image data from render web service and masks out resin areas.
 *
 * @author Stephan Preibisch
 */
public class MaskedResinLayerLoader
        extends RenderLayerLoader {

    private final double sigma;
    private final double renderScale;
    private final double relativeContentThreshold;
    private final float maskIntensity;

    /**
     * @param layerUrlPattern           render parameters URL pattern for each layer to be loaded
     *                                  that includes one '%s' element for z substitution
     *                                  (e.g. http://[base-url]/owner/o/project/p/stack/s/z/%s/box/0,0,2000,2000,0.125/render-parameters).
     * @param sortedZList               sorted list of z values for the layers to be loaded.
     * @param imageProcessorCache       source data cache (only useful for caching source masks).
     * @param sigma                     standard deviation for gaussian convolution.
     * @param renderScale               scale for layer rendering.
     * @param relativeContentThreshold  threshold intensity that identifies content.
     * @param maskIntensity             all values where mask is less than this value will be ignored.
     *
     * @throws IllegalArgumentException
     *   if an invalid layer URL pattern is specified.
     */
    public MaskedResinLayerLoader(final String layerUrlPattern,
                                  final List<Double> sortedZList,
                                  final ImageProcessorCache imageProcessorCache,
                                  final double sigma,
                                  final double renderScale,
                                  final double relativeContentThreshold,
                                  final float maskIntensity)
            throws IllegalArgumentException {

        super(layerUrlPattern, sortedZList, imageProcessorCache);
        this.sigma = sigma;
        this.renderScale = renderScale;
        this.relativeContentThreshold = relativeContentThreshold;
        this.maskIntensity = maskIntensity;
    }

    @Override
    public FloatProcessors getProcessors(final int layerIndex) {

        final FloatProcessors processors = super.getProcessors(layerIndex);
        processors.mask = buildResinMask(processors);

        return processors;
    }

    private FloatProcessor buildResinMask(final FloatProcessors processors) {

        LOG.debug("buildResinMask: entry, filtering image");

        final StopWatch stopWatch = StopWatch.createAndStart();

        final FloatProcessor image = processors.image;

        final int layerWidth = image.getWidth();
        final int layerHeight = image.getHeight();
        final int layerPixelCount = layerWidth * layerHeight;

        final FloatProcessor mask;
        if (processors.mask == null) {
            final float[] emptyMaskPixels = new float[layerPixelCount];
            Arrays.fill(emptyMaskPixels, maskIntensity);
            mask = new FloatProcessor(layerWidth, layerHeight, emptyMaskPixels);
        }  else {
            mask = processors.mask;
        }

        // At this point, mask only includes masked areas for each individual tile in the layer.
        // We also need to mask any layer pixels that are completely outside of the transformed tiles.

        final StopWatch outsideTileMaskStopWatch = StopWatch.createAndStart();

        int zeroValuePixelCount = 0;
        final float[] layerImagePixels = (float[]) image.getPixels();
        final float[] layerMaskPixels = (float[]) mask.getPixels();
        for (int i = 0; i < layerImagePixels.length; i++) {
            // only run expensive inverse transformation to check if input image is hit when pixel is black (background)
            if (layerImagePixels[i] == 0) {
                zeroValuePixelCount++;
                // TODO: review inverse transformation performance issues with SP
                // - is there a faster way and if not, is this really necessary ?
                // - explain earlier comment: "should ideally be a pixel away from the border"
                final int x = i % layerWidth;
                final int y = i / layerWidth;
//                if (! processors.renderParameters.isRenderedCoordinateInsideTiles(x, y)) {
                    layerMaskPixels[i] = 0;
//                }
//                if (zeroValuePixelCount % 10000 == 0) {
//                    LOG.debug("buildResinMask: checked {} zero value pixels", zeroValuePixelCount);
//                }
            }
        }

        final double zeroPixelPercentage = Math.round((zeroValuePixelCount * 10000.0) / layerPixelCount) / 100.0 ;
        outsideTileMaskStopWatch.stop();

        LOG.debug("buildResinMask: outsideTileMask derivation took {} for {} zero value pixels ({}% of total)",
                  outsideTileMaskStopWatch, zeroValuePixelCount, zeroPixelPercentage);

        final RandomAccessibleInterval<FloatType> imgA = ArrayImgs.floats(layerImagePixels, layerWidth, layerHeight);
        final RandomAccessibleInterval<FloatType> imgB = ArrayImgs.floats(layerMaskPixels, layerWidth, layerHeight);
        final float[] outP = new float[layerPixelCount];
        final Img<FloatType> out = ArrayImgs.floats(outP, layerWidth, layerHeight);

        weightedGauss(
                new double[]{sigma * renderScale, sigma * renderScale},
                Views.extendMirrorSingle(imgA),
                Views.extendBorder(imgB),
                out);

        final Cursor<FloatType> ic = Views.flatIterable(imgA).cursor();
        Cursor<FloatType> mc = Views.flatIterable(imgB).cursor();
        Cursor<FloatType> pc = Views.flatIterable(out).cursor();

        while (pc.hasNext()) {
            final FloatType p = pc.next();
            final FloatType m = mc.next();
            final FloatType j = ic.next();

            if (m.get() < maskIntensity) {
                p.set(0);
            } else {
                p.set(Math.max(0, j.get() - p.get()));
            }
        }

        //Img< FloatType > outCopy = out.copy();

        weightedGauss(
                new double[]{sigma * renderScale, sigma * renderScale},
                Views.extendMirrorSingle(out),
                Views.extendBorder(imgB),
                out);

        mc = Views.flatIterable(imgB).cursor();
        pc = Views.flatIterable(out).cursor();

        while (pc.hasNext()) {
            final FloatType p = pc.next();
            final FloatType m = mc.next();

            if (m.get() < maskIntensity || p.get() < relativeContentThreshold) {
                p.set(0);
            } else {
                p.set(maskIntensity);
            }
        }

        stopWatch.stop();

        LOG.debug("buildResinMask: exit, took {}", stopWatch);

        return new FloatProcessor(layerWidth, layerHeight, outP);
    }

    public static void weightedGauss(final double[] sigmas,
                                      final RandomAccessible<FloatType> source,
                                      final RandomAccessible<FloatType> weight,
                                      final RandomAccessibleInterval<FloatType> output) {

        final FloatType type = new FloatType();
        final RandomAccessible<FloatType> weightedSource =
                Converters.convert(source, weight, (i1, i2, o) -> o.setReal(
                        i1.getRealDouble() * i2.getRealDouble()), type);

        final long[] min = new long[output.numDimensions()];
        for (int d = 0; d < min.length; ++d) {
            min[d] = output.min(d);
        }

        final RandomAccessibleInterval<FloatType> sourceTmp =
                Views.translate(new ArrayImgFactory<>(type).create(output), min);
        final RandomAccessibleInterval<FloatType> weightTmp =
                Views.translate(new ArrayImgFactory<>(type).create(output), min);

        Gauss3.gauss(sigmas, weightedSource, sourceTmp, 1);
        Gauss3.gauss(sigmas, weight, weightTmp, 1);

        final Cursor<FloatType> i = Views.flatIterable(Views.interval(source, sourceTmp)).cursor();
        final Cursor<FloatType> s = Views.flatIterable(sourceTmp).cursor();
        final Cursor<FloatType> w = Views.flatIterable(weightTmp).cursor();
        final Cursor<FloatType> o = Views.flatIterable(output).cursor();

        while (o.hasNext()) {
            final double w0 = w.next().getRealDouble();

            if (w0 == 0) {
                o.next().set(i.next());
                s.fwd();
            } else {
                o.next().setReal(s.next().getRealDouble() / w0);
                i.fwd();
            }
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(MaskedResinLayerLoader.class);

}

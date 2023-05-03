package org.janelia.alignment.filter;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import org.janelia.alignment.intensity.QuadraticIntensityMap;

import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.type.numeric.real.FloatType;

public class QuadraticIntensityMap8BitFilter extends IntensityMap8BitFilter {

    // empty constructor required to create instances from specifications
    @SuppressWarnings("unused")
    public QuadraticIntensityMap8BitFilter() {
        this(0, 0, 0, new double[0][0]);
    }

    public QuadraticIntensityMap8BitFilter(final int numberOfRegionRows,
                                           final int numberOfRegionColumns,
                                           final int coefficientsPerRegion,
                                           final double[][] coefficients) {
        super(numberOfRegionRows, numberOfRegionColumns, coefficientsPerRegion, coefficients);
    }

    /**
     * Adapted from the render-java-ws-client module implementation of:
     *   org.janelia.render.client.intensityadjust.virtual.OnTheFlyIntensityQuadratic#computeIntensityCorrectionOnTheFly
     *
     * @param ip     pixels to process.
     * @param scale  current render scale.
     */
    @Override
    public void process(final ImageProcessor ip,
                        final double scale) {

        final FloatProcessor as = new FloatProcessor(numberOfRegionColumns, numberOfRegionRows);
        final FloatProcessor bs = new FloatProcessor(numberOfRegionColumns, numberOfRegionRows);
        final FloatProcessor cs = new FloatProcessor(numberOfRegionColumns, numberOfRegionRows);

        final FloatProcessor fp = ip.convertToFloatProcessor();
        fp.resetMinAndMax();
        final double min = 0;
        final double max = 255;
        final double delta = max - min;

        for (int i = 0; i < coefficients.length; ++i) {
            final double[] abc = coefficients[i];

            // mapping coefficients of polynomial on [0, 1] x [0, 1]
            // to coefficients of polynomial of the same shape on [min, max] x [min, max]
            final double anew = abc[0] / delta;
            as.setf(i, (float) anew);
            bs.setf(i, (float) (min * anew * (min / delta - 2) + abc[1]));
            cs.setf(i, (float) (min * (min * anew - abc[1]) + delta * abc[2] + min));
        }
        final ImageStack coefficientsStack = new ImageStack(numberOfRegionColumns, numberOfRegionRows);
        coefficientsStack.addSlice(as);
        coefficientsStack.addSlice(bs);
        coefficientsStack.addSlice(cs);

        final QuadraticIntensityMap<FloatType> map =
                new QuadraticIntensityMap<FloatType>(ImagePlusImgs.from(new ImagePlus("", coefficientsStack)));

        final long[] dims = new long[]{ip.getWidth(), ip.getHeight()};
        final Img<FloatType> img = ArrayImgs.floats((float[])fp.getPixels(), dims);

        map.run(img);
        fp.setMinAndMax(0, 255);
        ip.setPixels(0, fp);
    }
}

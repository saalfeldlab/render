package org.janelia.alignment.filter;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import org.janelia.alignment.intensity.LinearIntensityMap;

import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.type.numeric.real.FloatType;

public class LinearIntensityMap8BitFilter
        extends IntensityMap8BitFilter {

    // empty constructor required to create instances from specifications
    @SuppressWarnings("unused")
    public LinearIntensityMap8BitFilter() {
        this(0, 0, 0, new double[0][0]);
    }

    public LinearIntensityMap8BitFilter(final int numberOfRegionRows,
                                        final int numberOfRegionColumns,
                                        final int coefficientsPerRegion,
                                        final double[][] coefficients) {
        super(numberOfRegionRows, numberOfRegionColumns, coefficientsPerRegion, coefficients);
    }


    /**
     * Adapted from render-java-ws-client module implementation of:
     *   org.janelia.render.client.intensityadjust.virtual.OnTheFlyIntensity#computeIntensityCorrectionOnTheFly
     *
     * @param ip     pixels to process.
     * @param scale  current render scale.
     */
    @Override
    public void process(final ImageProcessor ip,
                        final double scale) {

        final FloatProcessor as = new FloatProcessor(numberOfRegionColumns, numberOfRegionRows);
        final FloatProcessor bs = new FloatProcessor(numberOfRegionColumns, numberOfRegionRows);

        // this filter always produces 8-bit corrected output
        final FloatProcessor fp = ip.convertToFloatProcessor();
        fp.resetMinAndMax();
        final double min = 0;
        final double max = 255;

        for (int i = 0; i < coefficients.length; ++i) {
            final double[] ab = coefficients[i];

            /* coefficients mapping into existing [min, max] */
            as.setf(i, (float) ab[0]);
            bs.setf(i, (float) ((max - min) * ab[1] + min - ab[0] * min));
        }
        final ImageStack coefficientsStack = new ImageStack(numberOfRegionColumns, numberOfRegionRows);
        coefficientsStack.addSlice(as);
        coefficientsStack.addSlice(bs);

        final LinearIntensityMap<FloatType> map =
                new LinearIntensityMap<FloatType>(
                        ImagePlusImgs.from(
                                new ImagePlus("", coefficientsStack)
                        )
                );

        final long[] dims = new long[]{ip.getWidth(), ip.getHeight()};
        final Img<FloatType> img = ArrayImgs.floats((float[]) fp.getPixels(), dims);

        map.run(img);

        // Need to reset intensity range back to full 8-bit before converting to byte processor!
        fp.setMinAndMax(0, 255);
        ip.setPixels(0, fp);
    }
}

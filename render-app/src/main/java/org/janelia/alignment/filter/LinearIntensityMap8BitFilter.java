package org.janelia.alignment.filter;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import java.util.HashMap;
import java.util.Map;

import org.janelia.alignment.intensity.LinearIntensityMap;

import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.type.numeric.real.FloatType;

public class LinearIntensityMap8BitFilter
        implements Filter {

    private int numberOfRegionRows;
    private int numberOfRegionColumns;
    private int coefficientsPerRegion;
    private double[][] coefficients;

    // empty constructor required to create instances from specifications
    @SuppressWarnings("unused")
    public LinearIntensityMap8BitFilter() {
        this(0, 0, 0, new double[0][0]);
    }

    public LinearIntensityMap8BitFilter(final int numberOfRegionRows,
                                        final int numberOfRegionColumns,
                                        final int coefficientsPerRegion,
                                        final double[][] coefficients) {
        this.numberOfRegionRows = numberOfRegionRows;
        this.numberOfRegionColumns = numberOfRegionColumns;
        this.coefficientsPerRegion = coefficientsPerRegion;
        this.coefficients = coefficients;
    }

    public int getNumberOfRegionRows() {
        return numberOfRegionRows;
    }

    public double[][] getCoefficients() {
        return coefficients;
    }

    @Override
    public void init(final Map<String, String> params) {
        final String[] values = Filter.getCommaSeparatedStringParameter(DATA_STRING_NAME, params);
        if (values.length < 4) {
            throw new IllegalArgumentException(DATA_STRING_NAME +
                                               " must have pattern <numberOfRegionRows>,<numberOfRegionColumns>,<coefficientsPerRegion>,[coefficient]...");
        }
        this.numberOfRegionRows = Integer.parseInt(values[0]);
        this.numberOfRegionColumns = Integer.parseInt(values[1]);
        this.coefficientsPerRegion = Integer.parseInt(values[2]);
        final int numberOfRegions = this.numberOfRegionRows * this.numberOfRegionColumns;

        final int expectedNumberOfCoefficients = numberOfRegions * this.coefficientsPerRegion;
        final int actualNumberOfCoefficients = values.length - 3;
        if (actualNumberOfCoefficients != expectedNumberOfCoefficients) {
            throw new IllegalArgumentException(DATA_STRING_NAME + " contains " + actualNumberOfCoefficients +
                                               " coefficient values instead of " + expectedNumberOfCoefficients);
        }

        this.coefficients = new double[numberOfRegions][this.coefficientsPerRegion];
        int region = 0;
        for (int i = 3; i < values.length; i+=this.coefficientsPerRegion) {
            this.coefficients[region][0] = Double.parseDouble(values[i]);
            this.coefficients[region][1] = Double.parseDouble(values[i+1]);
            region++;
        }
    }

    public String toDataString() {
        final StringBuilder sb = new StringBuilder();
        sb.append(numberOfRegionRows).append(',');
        sb.append(numberOfRegionColumns).append(',');
        sb.append(coefficientsPerRegion);
        for (final double[] regionCoefficients : coefficients) {
            for (final double coefficient : regionCoefficients) {
                sb.append(',').append(coefficient);
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return this.toDataString();
    }

    @Override
    public Map<String, String> toParametersMap() {
        final Map<String, String> map = new HashMap<>();
        map.put(DATA_STRING_NAME, this.toDataString());
        return map;
    }

    /**
     * Adapted from Preibisch's render-java-ws-client module implementation of:
     *   org.janelia.render.client.intensityadjust.virtual.OnTheFlyIntensity#computeIntensityCorrectionOnTheFly
     *
     * @param ip     pixels to process.
     * @param scale  current render scale.
     *
     * @return intensity corrected result.
     */
    @Override
    public ImageProcessor process(final ImageProcessor ip,
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
        return fp.convertToByteProcessor();
    }
}

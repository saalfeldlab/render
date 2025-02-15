package org.janelia.alignment.intensity;

import net.imglib2.util.IntervalIndexer;
import net.imglib2.util.Intervals;

import static net.imglib2.util.Util.safeInt;

/**
 * Holds flattened coefficient array and dimensions.
 */
public class Coefficients {

    private final int[] size;
    private final int[] strides;

    /**
     * {@code flattenedCoefficients[i]} holds the flattened array of the i-the coefficient.
     * That is, for linear map {@code y=a*x+b}, {@code flattenedCoefficients[0]} holds all the {@code a}s and
     * {@code flattenedCoefficients[1]} holds all the {@code b}s.
     */
    final float[][] flattenedCoefficients;

    public Coefficients(
            final double[][] coefficients,
            final int... fieldDimensions) {
        this((c, f) -> coefficients[f][c], coefficients[0].length, fieldDimensions);
    }

    @FunctionalInterface
    public interface CoefficientFunction {
        double apply(int coefficientIndex, int flattenedFieldIndex);
    }

    public Coefficients(
            final CoefficientFunction coefficients,
            final int numCoefficients,
            final int... fieldDimensions) {
        final int numElements = safeInt(Intervals.numElements(fieldDimensions));
        size = fieldDimensions.clone();
        strides = IntervalIndexer.createAllocationSteps(size);
        flattenedCoefficients = new float[numCoefficients][numElements];
        for (int j = 0; j < numCoefficients; ++j) {
            for (int i = 0; i < numElements; ++i) {
                flattenedCoefficients[j][i] = (float) coefficients.apply(j, i);
            }
        }
    }

    int size(final int d) {
        return size[d];
    }

    int stride(final int d) {
        return strides[d];
    }

    int numDimensions() {
        return size.length;
    }
}

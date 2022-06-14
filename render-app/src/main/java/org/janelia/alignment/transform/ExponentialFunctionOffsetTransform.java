package org.janelia.alignment.transform;

import mpicbg.trakem2.transform.CoordinateTransform;

/**
 * Transform that subtracts <pre> a * exp(-b*x) + c </pre> from all x (or <pre> a * exp(-b*y) + c </pre> from all y).
 */
public class ExponentialFunctionOffsetTransform
        extends MultiParameterSingleDimensionTransform {

    public ExponentialFunctionOffsetTransform() {
        this(0, 0, 0,0);
    }

    public ExponentialFunctionOffsetTransform(final double a,
                                              final double b,
                                              final double c,
                                              final int dimension) {
        super(new double[] {a, b, c}, dimension);
    }

    @Override
    public int getNumberOfCoefficients() {
        return 3;
    }

    @Override
    public void applyInPlace(final double[] location) {
        location[dimension] -= coefficients[0] * Math.exp(-coefficients[1] * location[dimension]) + coefficients[2];
    }

    @Override
    public CoordinateTransform copy() {
        return new ExponentialFunctionOffsetTransform(coefficients[0],
                                                      coefficients[1],
                                                      coefficients[2],
                                                      dimension);
    }
}

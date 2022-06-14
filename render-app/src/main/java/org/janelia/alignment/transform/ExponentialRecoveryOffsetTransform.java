package org.janelia.alignment.transform;

import mpicbg.trakem2.transform.CoordinateTransform;

/**
 * Transform that subtracts <pre> a * (1 - exp(-b*x)) + c </pre> from all x
 * (or <pre> a * (1 - exp(-b*y)) + c </pre> from all y).
 */
public class ExponentialRecoveryOffsetTransform
        extends MultiParameterSingleDimensionTransform {

    public ExponentialRecoveryOffsetTransform() {
        this(0, 0, 0,0);
    }

    @Override
    public int getNumberOfCoefficients() {
        return 3;
    }


    public ExponentialRecoveryOffsetTransform(final double a,
                                              final double b,
                                              final double c,
                                              final int dimension) {
        super(new double[] {a, b, c}, dimension);
    }

    @Override
    public void applyInPlace(final double[] location) {
        location[dimension] -=
                coefficients[0] * (1 - Math.exp(-coefficients[1] * location[dimension])) + coefficients[2];
    }

    @Override
    public CoordinateTransform copy() {
        return new ExponentialRecoveryOffsetTransform(coefficients[0],
                                                      coefficients[1],
                                                      coefficients[2],
                                                      dimension);
    }
}

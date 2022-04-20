package org.janelia.alignment.transform;

import mpicbg.trakem2.transform.CoordinateTransform;

/**
 * Transform that subtracts <pre> a * (1 - e^-b*x) + c </pre> from all x
 * (or <pre> a * (1 - e^-b*y) + c </pre> from all y).
 */
public class ExponentialRecoveryOffsetTransform
        extends ThreeParameterSingleDimensionTransform {

    public ExponentialRecoveryOffsetTransform() {
        this(0, 0, 0,0);
    }

    public ExponentialRecoveryOffsetTransform(final double a,
                                              final double b,
                                              final double c,
                                              final int dimension) {
        super(a, b, c, dimension);
    }

    @Override
    public void applyInPlace(final double[] location) {
        location[dimension] -= a * (1 - Math.exp(-b * location[dimension])) + c;
    }

    @Override
    public CoordinateTransform copy() {
        return new ExponentialRecoveryOffsetTransform(a, b, c, dimension);
    }
}

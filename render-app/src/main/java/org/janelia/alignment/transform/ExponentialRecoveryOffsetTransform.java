package org.janelia.alignment.transform;

import mpicbg.trakem2.transform.CoordinateTransform;

/**
 * Transform that adds <pre> a * (1 - e^-b*x) + c </pre> to all x (or <pre> a * (1 - e^-b*y) + c </pre> to all y).
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
        // TODO: verify offset is what Preibisch really wants
        location[dimension] += a * (1 - Math.exp(-b * location[dimension])) + c;
    }

    @Override
    public CoordinateTransform copy() {
        return new ExponentialRecoveryOffsetTransform(a, b, c, dimension);
    }
}

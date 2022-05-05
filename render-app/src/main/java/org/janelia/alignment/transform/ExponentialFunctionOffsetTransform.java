package org.janelia.alignment.transform;

import mpicbg.trakem2.transform.CoordinateTransform;

/**
 * Transform that subtracts <pre> a * e^-b*x + c </pre> from all x (or <pre> a * e^-b*y + c </pre> from all y).
 */
public class ExponentialFunctionOffsetTransform
        extends ThreeParameterSingleDimensionTransform {

    public ExponentialFunctionOffsetTransform() {
        this(0, 0, 0,0);
    }

    public ExponentialFunctionOffsetTransform(final double a,
                                              final double b,
                                              final double c,
                                              final int dimension) {
        super(a, b, c, dimension);
    }

    @Override
    public void applyInPlace(final double[] location) {
        location[dimension] -= a * Math.exp(-b * location[dimension]) + c;
    }

    @Override
    public CoordinateTransform copy() {
        return new ExponentialFunctionOffsetTransform(a, b, c, dimension);
    }
}

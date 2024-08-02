package org.janelia.alignment.transform;

import mpicbg.trakem2.transform.CoordinateTransform;

/**
 * Transform that adds <pre> a * exp(-b*x) + c </pre> to all x (or <pre> a * exp(-b*y) + c </pre> from all y).
 * This is the same as ExponentialFunctionOffsetTransform, but with a different sign. This transform does **not**
 * correctly address the multi-SEM scanning artifacts, but is used to recapitulate Ken's original prototype alignment
 * for the wafer_53 data.
 */
public class ExponentialFunctionOffsetTransformWithWrongSign
        extends MultiParameterSingleDimensionTransform {

    public ExponentialFunctionOffsetTransformWithWrongSign() {
        this(0, 0, 0,0);
    }

    public ExponentialFunctionOffsetTransformWithWrongSign(final double a,
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
        location[dimension] += coefficients[0] * Math.exp(-coefficients[1] * location[dimension]) + coefficients[2];
    }

    @Override
    public CoordinateTransform copy() {
        return new ExponentialFunctionOffsetTransformWithWrongSign(coefficients[0],
																   coefficients[1],
																   coefficients[2],
																   dimension);
    }
}

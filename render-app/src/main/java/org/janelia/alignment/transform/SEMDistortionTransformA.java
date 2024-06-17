package org.janelia.alignment.transform;

import mpicbg.trakem2.transform.CoordinateTransform;

/**
 * Transform that adds <pre>a * exp(-x/b) + c * exp(-x/d)</pre> to all x
 * (or <pre>a * exp(-y/b) + c * exp(-y/d)</pre> to all y).
 */
public class SEMDistortionTransformA
        extends MultiParameterSingleDimensionTransform {

    public SEMDistortionTransformA() {
        this(0, 0, 0, 0, 0);
    }

    public SEMDistortionTransformA(final double a,
                                   final double b,
                                   final double c,
                                   final double d,
                                   final int dimension) {
        super(new double[] {a, b, c, d}, dimension);
    }

    @Override
    public int getNumberOfCoefficients() {
        return 4;
    }

    @Override
    public void applyInPlace(final double[] location) {
        location[dimension] += (coefficients[0] * Math.exp(-location[dimension] / coefficients[1])) +
                               (coefficients[2] * Math.exp(-location[dimension] / coefficients[3]));
    }

    @Override
    public CoordinateTransform copy() {
        return new SEMDistortionTransformA(coefficients[0],
                                           coefficients[1],
                                           coefficients[2],
                                           coefficients[3],
                                           dimension);
    }

    /** Default correction transform for all FIBSEM volumes. */
    public static final SEMDistortionTransformA DEFAULT_FIBSEM_CORRECTION_TRANSFORM =
            new SEMDistortionTransformA(19.4, 64.8, 24.4, 972.0, 0);
}

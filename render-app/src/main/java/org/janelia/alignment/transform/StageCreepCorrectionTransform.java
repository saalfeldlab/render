package org.janelia.alignment.transform;

import mpicbg.trakem2.transform.CoordinateTransform;

/**
 * Forward transform for piezo stage creep correction in multi-SEM imaging.
 *
 * <p>The backward map (used by the Python prototype via cv2.remap) is:</p>
 * <pre>y_source = y_world + a * exp(-y_world / b) + c * exp(-y_world / d)</pre>
 *
 * <p>This class implements the exact forward (source &rarr; world) transform by numerically
 * inverting the backward map using Newton's method: given {@code y_s}, find {@code y_w} such that
 * {@code y_w + a * exp(-y_w / b) + c * exp(-y_w / d) = y_s}.</p>
 *
 * <p>Coefficients: a, b, c, d (same as {@link SEMDistortionTransformA}).
 * Data string format: {@code "a,b,c,d,dimension"}.</p>
 */
public class StageCreepCorrectionTransform
        extends MultiParameterSingleDimensionTransform {

    private static final int MAX_ITERATIONS = 10;
    private static final double TOLERANCE = 1e-9;

    public StageCreepCorrectionTransform() {
        this(0, 0, 0, 0, 0);
    }

    public StageCreepCorrectionTransform(final double a,
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

    /**
     * Forward transform (source &rarr; world): given y_s, computes y_w by solving
     * {@code y_w + a * exp(-y_w / b) + c * exp(-y_w / d) = y_s} using Newton's method.
     */
    @Override
    public void applyInPlace(final double[] location) {
        final double a = coefficients[0];
        final double b = coefficients[1];
        final double c = coefficients[2];
        final double d = coefficients[3];
        final double y_s = location[dimension];

        // solve g(y_w) = y_w + a*exp(-y_w/b) + c*exp(-y_w/d) - y_s = 0
        // g'(y_w) = 1 - (a/b)*exp(-y_w/b) - (c/d)*exp(-y_w/d)
        double y_w = y_s; // initial guess
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            final double expB = Math.exp(-y_w / b);
            final double expD = Math.exp(-y_w / d);
            final double g = y_w + a * expB + c * expD - y_s;
            if (Math.abs(g) < TOLERANCE) {
                break;
            }
            final double gPrime = 1.0 - (a / b) * expB - (c / d) * expD;
            y_w -= g / gPrime;
        }

        location[dimension] = y_w;
    }

    @Override
    public CoordinateTransform copy() {
        return new StageCreepCorrectionTransform(coefficients[0],
                                                 coefficients[1],
                                                 coefficients[2],
                                                 coefficients[3],
                                                 dimension);
    }
}

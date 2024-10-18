package org.janelia.alignment.inpainting;

import java.util.Random;

/**
 * A statistic that yields a small perturbation of a given 2D direction for each sample.
 */
public class AnisotropicDirection2D implements DirectionalStatistic {

	private final Random random;
	private final double[] primalAxis;
	private final double[] secondaryAxis;
	private final double perturbation;

	/**
	 * Creates a new statistic with a random seed.
	 */
	public AnisotropicDirection2D(final double[] primalAxis, final double perturbation) {
		this(primalAxis, perturbation, new Random());
	}

	/**
	 * Creates a new statistic with the given random number generator.
	 *
	 * @param random the random number generator to use
	 */
	public AnisotropicDirection2D(final double[] primalAxis, final double perturbation, final Random random) {
		final double norm = Math.sqrt(primalAxis[0] * primalAxis[0] + primalAxis[1] * primalAxis[1]);
		this.primalAxis = new double[] { primalAxis[0] / norm, primalAxis[1] / norm };
		this.secondaryAxis = new double[] { -primalAxis[1] / norm, primalAxis[0] / norm };
		this.perturbation = perturbation;
		this.random = random;
	}

	@Override
	public void sample(final double[] direction) {
		// TODO: this should be a von Mises distribution instead of this homegrown implementation
		final int sign = random.nextBoolean() ? 1 : -1;
		final double eps = perturbation * random.nextGaussian();
		final double norm = 1 + eps * eps; // because axes are orthonormal
		direction[0] = (sign * primalAxis[0] + eps * secondaryAxis[0]) / norm;
		direction[1] = (sign * primalAxis[1] + eps * secondaryAxis[1]) / norm;
	}
}

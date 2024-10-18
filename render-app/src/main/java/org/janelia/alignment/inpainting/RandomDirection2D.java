package org.janelia.alignment.inpainting;

import java.util.Random;

/**
 * A statistic that yields a completely random 2D direction for each sample.
 */
public class RandomDirection2D implements DirectionalStatistic {

	private final Random random;

	/**
	 * Creates a new statistic with a random seed.
	 */
	public RandomDirection2D() {
		this(new Random());
	}

	/**
	 * Creates a new statistic with the given random number generator.
	 *
	 * @param random the random number generator to use
	 */
	public RandomDirection2D(final Random random) {
		this.random = random;
	}

	@Override
	public void sample(final double[] direction) {
		final double angle = random.nextDouble() * 2 * Math.PI;
		direction[0] = Math.cos(angle);
		direction[1] = Math.sin(angle);
	}
}

package org.janelia.alignment.destreak;

import java.util.Random;

/**
 * A strategy that yields a completely random direction for each ray.
 */
public class RandomRayDirection2D implements RayDirectionStrategy {

	private final Random random;

	/**
	 * Creates a new random ray direction with a random seed.
	 */
	public RandomRayDirection2D() {
		this(new Random());
	}

	/**
	 * Creates a new random ray direction with the given random number generator.
	 *
	 * @param random the random number generator to use
	 */
	public RandomRayDirection2D(final Random random) {
		this.random = random;
	}

	@Override
	public void initializeNextDirection(final double[] direction) {
		final double angle = random.nextDouble() * 2 * Math.PI;
		direction[0] = Math.cos(angle);
		direction[1] = Math.sin(angle);
	}
}

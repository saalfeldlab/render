package org.janelia.alignment.inpainting;

/**
 * Interface for distributions that model the direction of a ray used in {@link RayCastingInpainter}.
 */
public interface DirectionalStatistic {

	/**
	 * Initializes the direction of the next ray. The array that is passed in is filled with the direction.
	 *
	 * @param direction the array in which to initialize the direction
	 */
	void sample(double[] direction);
}

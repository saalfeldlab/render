package org.janelia.alignment.destreak;

/**
 * Interface for strategies that determine the direction of a ray used in {@link RayCastingInpainter}.
 */
public interface RayDirectionStrategy {

	/**
	 * Initializes the direction of the next ray. The array that is passed in is filled with the direction.
	 *
	 * @param direction the array in which to initialize the direction
	 */
	void initializeNextDirection(double[] direction);
}

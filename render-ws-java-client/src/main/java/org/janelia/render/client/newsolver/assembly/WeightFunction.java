package org.janelia.render.client.newsolver.assembly;

/**
 * A function that computes a weight for a given point in space.
 * The weight is a double value larger than 0. The higher the weight, the more important the point is.
 *
 * @author Michael Innerberger
 */
@FunctionalInterface
public interface WeightFunction {
	double compute(final double x, final double y, final double z);
}

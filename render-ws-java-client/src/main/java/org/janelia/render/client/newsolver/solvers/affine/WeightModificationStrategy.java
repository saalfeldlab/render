package org.janelia.render.client.newsolver.solvers.affine;

import mpicbg.models.Tile;
import org.janelia.render.client.parameter.BlockOptimizerParameters;
import org.janelia.render.client.parameter.BlockOptimizerParameters.AlignmentModelType;

import java.util.HashMap;
import java.util.Map;

public interface WeightModificationStrategy {
	/**
	 * Modifies the weights based on the provided tile. The new weights may
	 * depend on the tile's properties, such as number of neighbors or position.
	 *
	 * @param weights the current weights as a map of model name to weight
	 * @param tile the tile for which the weights are being modified
	 * @return the new weights
	 */
	Map<String, Double> applyTo(final Map<String, Double> weights, final Tile<?> tile);

	/**
	 * Creates a WeightModificationStrategy that does not modify the weights.
	 */
	static WeightModificationStrategy none() {
		return (weights, tile) -> weights;
	}

	/**
	 * Creates a WeightModificationStrategy that modifies the weights based on the
	 * number of matches in the tile, using a sigmoid function between 0 and 1 that
	 * satisfies sigmoid(lowDecile) = 0.1 and sigmoid(highDecile) = 0.9.
	 * This is similar to a Weibull stretched exponential.
	 *
	 * @param lowDecile the low decile value for scaling
	 * @param highDecile the high decile value for scaling
	 * @return a WeightModificationStrategy that applies sigmoidal scaling
	 */
	static WeightModificationStrategy  sigmoid(final int lowDecile, final int highDecile) {
		// Compute the parameters for the sigmoid function
		final double enumerator = Math.log(-Math.log(0.1)) - Math.log(-Math.log(0.9));
		final double b = enumerator / (Math.log(highDecile) - Math.log(lowDecile));
		final double a = lowDecile * Math.pow(-Math.log(0.9), -1 / b);

		// Create the WeightModificationStrategy that applies the sigmoid function
		return (weights, tile) -> {
			final Map<String, Double> modifiedWeights = new HashMap<>(weights);
			final int nMatches = tile.getMatches().size();
			final double factor = 1 - Math.exp(-Math.pow(nMatches / a, b));
			modifiedWeights.compute(AlignmentModelType.AFFINE.name(), (key, affine) -> affine * factor);
			return modifiedWeights;
		};
	}

}

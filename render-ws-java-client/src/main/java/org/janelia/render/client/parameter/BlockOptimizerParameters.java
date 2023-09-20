package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;
import mpicbg.models.AffineModel2D;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.RigidModel2D;
import mpicbg.models.TranslationModel2D;
import org.janelia.render.client.solver.StabilizingAffineModel2D;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parameters for optimization of distributed blocks.
 * <p>
 * Alignment of the actual blocks that is performed in parallel, models are hardcoded:
 * AFFINE, regularized with RIGID, regularized with Translation, regularized with RegularizingModel (Constant or Stabilizing)
 * and a set of decreasing lambdas (see below)
 *
 * @author Michael Innerberger
 */
public class BlockOptimizerParameters implements Serializable {
	@Parameter(
			names = "--blockOptimizerLambdasRigid",
			description = "Explicit optimizer lambda values for the rigid regularizer, by default optimizer loops through lambdas (1.0,0.5,0.1,0.01)",
			variableArity = true
	)
	public List<Double> lambdasRigid = new ArrayList<>(Arrays.asList(1.0, 0.5, 0.1, 0.01));

	@Parameter(
			names = "--blockOptimizerLambdasTranslation",
			description = "Explicit optimizer lambda values for the translation regularizer, by default optimizer loops through lambdas (1.0,0.5,0.1,0.01)",
			variableArity = true
	)
	public List<Double> lambdasTranslation = new ArrayList<>(Arrays.asList(0.5, 0.0, 0.0, 0.0));

	@Parameter(
			names = "--blockOptimizerLambdasRegularization",
			description = "Explicit optimizer lambda values for the Regularizer-model, by default optimizer loops through lambdas (0.05, 0.01, 0.0, 0.0)",
			variableArity = true
	)
	public List<Double> lambdasRegularization = new ArrayList<>(Arrays.asList(0.05, 0.01, 0.0, 0.0));

	@Parameter(
			names = "--blockOptimizerIterations",
			description = "Explicit num iterations for each lambda value (blockOptimizerLambdas), " +
					"by default optimizer uses (1000,1000,400,200), MUST MATCH SIZE of blockOptimizerLambdas",
			variableArity = true
	)
	public List<Integer> iterations = new ArrayList<>(Arrays.asList(1000, 1000, 400, 200));

	@Parameter(
			names = "--blockMaxPlateauWidth",
			description = "Explicit max plateau width block alignment for each lambda value (blockOptimizerLambdas), " +
					"by default optimizer uses (2500,250,100,50), MUST MATCH SIZE of blockOptimizerLambdas",
			variableArity = true
	)
	public List<Integer> maxPlateauWidth = new ArrayList<>(Arrays.asList(250, 250, 100, 50));

	@Parameter(
			names = "--blockMaxAllowedError",
			description = "Max allowed error block alignment (default: 10.0)"
	)
	public Double maxAllowedError = 10.0;

	public boolean isConsistent() {
		final int n = iterations.size();
		return n == maxPlateauWidth.size() &&
				n == lambdasRigid.size() &&
				n == lambdasTranslation.size() &&
				n == lambdasRegularization.size();
	}

	public InterpolatedAffineModel2D<InterpolatedAffineModel2D<InterpolatedAffineModel2D<AffineModel2D, RigidModel2D>, TranslationModel2D>, StabilizingAffineModel2D<RigidModel2D>>
		getModel() {

		// TODO: there are many loose ends (only one lambda value for translation, no regularization, etc.)
		return new InterpolatedAffineModel2D<>(
				new InterpolatedAffineModel2D<>(
						new InterpolatedAffineModel2D<>(
								new AffineModel2D(),
								new RigidModel2D(), lambdasRigid.get(0)),
						new TranslationModel2D(), lambdasTranslation.get(0)),
				new StabilizingAffineModel2D<>(new RigidModel2D()), 0.0);
	}
}

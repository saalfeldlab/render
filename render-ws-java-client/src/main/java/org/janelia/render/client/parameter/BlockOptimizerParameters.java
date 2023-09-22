package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;
import mpicbg.models.AffineModel2D;
import mpicbg.models.RigidModel2D;
import mpicbg.models.TranslationModel2D;
import org.janelia.render.client.newsolver.solvers.affine.AlignmentModel;
import org.janelia.render.client.newsolver.solvers.affine.AlignmentModel.AlignmentModelBuilder;
import org.janelia.render.client.solver.StabilizingAffineModel2D;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
		final int n = nRuns();
		return n == maxPlateauWidth.size() &&
				n == lambdasRigid.size() &&
				n == lambdasTranslation.size() &&
				n == lambdasRegularization.size();
	}

	public AlignmentModel getModel() {
		final AlignmentModelBuilder builder = AlignmentModel.configure()
				.addModel("affine", new AffineModel2D())
				.addModel("rigid", new RigidModel2D())
				.addModel("translation", new TranslationModel2D())
				.addModel("regularization", new StabilizingAffineModel2D<>(new RigidModel2D()));
		return builder.build();
	}

	public Map<String, Double> setUpZeroWeights() {
		return Map.of(
				"affine", 0.0,
				"rigid", 0.0,
				"translation", 0.0,
				"regularization", 0.0);
	}

	public Map<String, Double> getWeightsForRun(final int i) {
		// this translates the lambdas of the nested model to relative weights for the flat model
		// TODO: maybe input the weights directly?
		final double r = lambdasRigid.get(i);
		final double t = lambdasTranslation.get(i);
		final double reg = lambdasRegularization.get(i);
		return Map.of(
				"affine", (1-r) * (1-t) * (1-reg),
				"rigid", r * (1-t) * (1-reg),
				"translation", t * (1-reg),
				"regularization", reg);
	}

	public int nRuns() {
		return iterations.size();
	}
}

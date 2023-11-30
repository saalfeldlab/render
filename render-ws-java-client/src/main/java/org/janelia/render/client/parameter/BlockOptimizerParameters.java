package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;

import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import mpicbg.models.AffineModel2D;
import mpicbg.models.RigidModel2D;
import mpicbg.models.TranslationModel2D;

import org.janelia.render.client.newsolver.solvers.affine.AlignmentModel;
import org.janelia.render.client.newsolver.solvers.affine.AlignmentModel.AlignmentModelBuilder;
import org.janelia.render.client.solver.StabilizingAffineModel2D;

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
	public List<Double> lambdasRigid = Arrays.asList(1.0, 0.5, 0.1, 0.01);

	@Parameter(
			names = "--blockOptimizerLambdasTranslation",
			description = "Explicit optimizer lambda values for the translation regularizer, by default optimizer loops through lambdas (1.0,0.5,0.1,0.01)",
			variableArity = true
	)
	public List<Double> lambdasTranslation = Arrays.asList(0.5, 0.0, 0.0, 0.0);

	@Parameter(
			names = "--blockOptimizerLambdasRegularization",
			description = "Explicit optimizer lambda values for the Regularizer-model, by default optimizer loops through lambdas (0.05, 0.01, 0.0, 0.0)",
			variableArity = true
	)
	public List<Double> lambdasRegularization = Arrays.asList(0.05, 0.01, 0.0, 0.0);

	@Parameter(
			names = "--blockOptimizerIterations",
			description = "Explicit num iterations for each lambda value (blockOptimizerLambdas), " +
					"by default optimizer uses (1000,1000,400,200), MUST MATCH SIZE of blockOptimizerLambdas",
			variableArity = true
	)
	public List<Integer> iterations = Arrays.asList(1000, 1000, 400, 200);

	@Parameter(
			names = "--blockMaxPlateauWidth",
			description = "Explicit max plateau width block alignment for each lambda value (blockOptimizerLambdas), " +
					"by default optimizer uses (2500,250,100,50), MUST MATCH SIZE of blockOptimizerLambdas",
			variableArity = true
	)
	public List<Integer> maxPlateauWidth = Arrays.asList(250, 250, 100, 50);

	@Parameter(
			names = "--blockMaxAllowedError",
			description = "Max allowed error block alignment (default: 10.0)"
	)
	public Double maxAllowedError = 10.0;

	@Parameter(
			names = "--fixBlockBoundary",
			description = "Fix tiles that are on the boundary of that block and shared with another block (default: false)"
	)
	public Boolean fixBlockBoundary = false;

	public boolean isConsistent() {
		final int n = nRuns();
		return n == maxPlateauWidth.size() &&
				n == lambdasRigid.size() &&
				n == lambdasTranslation.size() &&
				n == lambdasRegularization.size();
	}

	public AlignmentModel getModel() {
		final AlignmentModelBuilder builder = AlignmentModel.configure()
				.addModel(AlignmentModelType.AFFINE.name(), new AffineModel2D())
				.addModel(AlignmentModelType.RIGID.name(), new RigidModel2D())
				.addModel(AlignmentModelType.TRANSLATION.name(), new TranslationModel2D())
				.addModel(AlignmentModelType.REGULARIZATION.name(), new StabilizingAffineModel2D<>(new RigidModel2D()));
		return builder.build();
	}

	public Map<String, Double> setUpZeroWeights() {
		return Map.of(
				AlignmentModelType.AFFINE.name(), 0.0,
				AlignmentModelType.RIGID.name(), 0.0,
				AlignmentModelType.TRANSLATION.name(), 0.0,
				AlignmentModelType.REGULARIZATION.name(), 0.0);
	}

	public Map<String, Double> getWeightsForRun(final int i) {
		// this translates the lambdas of the nested model to relative weights for the flat model
		// TODO: maybe input the weights directly?
		final double r = lambdasRigid.get(i);
		final double t = lambdasTranslation.get(i);
		final double reg = lambdasRegularization.get(i);
		return Map.of(
				AlignmentModelType.AFFINE.name(), (1-r) * (1-t) * (1-reg),
				AlignmentModelType.RIGID.name(), r * (1-t) * (1-reg),
				AlignmentModelType.TRANSLATION.name(), t * (1-reg),
				AlignmentModelType.REGULARIZATION.name(), reg);
	}

	public int nRuns() {
		return iterations.size();
	}

	public enum AlignmentModelType {
		AFFINE,
		RIGID,
		TRANSLATION,
		REGULARIZATION
	}
}

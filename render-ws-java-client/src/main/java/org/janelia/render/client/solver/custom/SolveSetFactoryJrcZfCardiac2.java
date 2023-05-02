package org.janelia.render.client.solver.custom;

import java.util.List;

import mpicbg.models.Affine2D;

import org.apache.commons.lang3.Range;

public class SolveSetFactoryJrcZfCardiac2
        extends SolveSetFactoryWithStitchFirstExclusions
{
	public SolveSetFactoryJrcZfCardiac2(
			final Affine2D<?> defaultGlobalSolveModel,
			final Affine2D<?> defaultBlockSolveModel,
			final Affine2D<?> defaultStitchingModel,
			final List<Double> defaultBlockOptimizerLambdasRigid,
			final List<Double> defaultBlockOptimizerLambdasTranslation,
			final List<Integer> defaultBlockOptimizerIterations,
			final List<Integer> defaultBlockMaxPlateauWidth,
			final int defaultMinStitchingInliers,
			final double defaultBlockMaxAllowedError,
			final double defaultDynamicLambdaFactor ) {
		super(
				defaultGlobalSolveModel,
				defaultBlockSolveModel,
				defaultStitchingModel,
				defaultBlockOptimizerLambdasRigid,
				defaultBlockOptimizerLambdasTranslation,
				defaultBlockOptimizerIterations,
				defaultBlockMaxPlateauWidth,
				defaultMinStitchingInliers,
				defaultBlockMaxAllowedError,
				defaultDynamicLambdaFactor );

		final Range<Integer> problemRange = Range.between(13780, 13810);

		// don't do stitch first for problem area
		addStitchFirstExclusionRange(problemRange);

		// use rigid align for problem area
		for (int z = problemRange.getMinimum(); z <= problemRange.getMaximum(); z++) {
			super.additionalIssues.put(z, "problem");
		}
	}

}

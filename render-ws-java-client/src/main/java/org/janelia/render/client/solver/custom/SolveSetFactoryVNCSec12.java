package org.janelia.render.client.solver.custom;

import java.util.List;

import mpicbg.models.Affine2D;

import org.apache.commons.lang3.Range;

public class SolveSetFactoryVNCSec12
        extends SolveSetFactoryWithStitchFirstExclusions
{
	public SolveSetFactoryVNCSec12(
			final Affine2D<?> defaultGlobalSolveModel,
			final Affine2D<?> defaultBlockSolveModel,
			final Affine2D<?> defaultStitchingModel,
			final List<Double> defaultBlockOptimizerLambdasRigid,
			final List<Double> defaultBlockOptimizerLambdasTranslation,
			final List<Integer> defaultBlockOptimizerIterations,
			final List<Integer> defaultBlockMaxPlateauWidth,
			final int defaultMinStitchingInliers,
			final double defaultBlockMaxAllowedError,
			final double defaultDynamicLambdaFactor )
	{
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

		addStitchFirstExclusionRange(Range.between(18330, 18730));
	}

}

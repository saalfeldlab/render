package org.janelia.render.client.solver.custom;

import java.util.List;

import org.apache.commons.lang3.Range;

import mpicbg.models.Affine2D;

public class SolveSetFactoryBRSec24 extends SolveSetFactoryWithStitchFirstExclusions
{
	public SolveSetFactoryBRSec24(
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

		final int lastTwoTileZ = 57325;
		final int exclusionMargin = 25;

		addStitchFirstExclusionRange(Range.between(lastTwoTileZ - exclusionMargin,
												   lastTwoTileZ + exclusionMargin));
	}

}

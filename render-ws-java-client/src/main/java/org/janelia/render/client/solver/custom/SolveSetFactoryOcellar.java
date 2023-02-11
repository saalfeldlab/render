package org.janelia.render.client.solver.custom;

import java.util.List;

import mpicbg.models.Affine2D;

import org.apache.commons.lang3.Range;

@SuppressWarnings("unused")
public class SolveSetFactoryOcellar
        extends SolveSetFactoryWithStitchFirstExclusions
{
    public SolveSetFactoryOcellar(
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
        super(defaultGlobalSolveModel,
              defaultBlockSolveModel,
              defaultStitchingModel,
              defaultBlockOptimizerLambdasRigid,
              defaultBlockOptimizerLambdasTranslation,
              defaultBlockOptimizerIterations,
              defaultBlockMaxPlateauWidth,
              defaultMinStitchingInliers,
              defaultBlockMaxAllowedError,
              defaultDynamicLambdaFactor);

        final int exclusionMargin = 30;

        final int firstProblemAreaZ = 1263;
        addStitchFirstExclusionRange(Range.between(firstProblemAreaZ - exclusionMargin,
                                                   firstProblemAreaZ + exclusionMargin));

        final int secondProblemAreaZ = 2097;
        addStitchFirstExclusionRange(Range.between(secondProblemAreaZ - exclusionMargin,
                                                   secondProblemAreaZ + exclusionMargin));
    }
}

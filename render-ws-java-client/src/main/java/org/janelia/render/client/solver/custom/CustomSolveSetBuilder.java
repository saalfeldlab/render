package org.janelia.render.client.solver.custom;

import java.lang.reflect.Constructor;
import java.util.List;

import mpicbg.models.Affine2D;

import org.janelia.render.client.solver.SolveSetFactory;
import org.janelia.render.client.zspacing.ZPositionCorrectionClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Uses reflection to build a {@link SolveSetFactory} instance.
 */
public class CustomSolveSetBuilder {

    public static SolveSetFactory build(final String solveSetFactoryClassName,
                                        final Affine2D< ? > defaultGlobalSolveModel,
                                        final Affine2D< ? > defaultBlockSolveModel,
                                        final Affine2D< ? > defaultStitchingModel,
                                        final List<Double> defaultBlockOptimizerLambdasRigid,
                                        final List<Double> defaultBlockOptimizerLambdasTranslation,
                                        final List<Integer> defaultBlockOptimizerIterations,
                                        final List<Integer> defaultBlockMaxPlateauWidth,
                                        final int defaultMinStitchingInliers,
                                        final double defaultBlockMaxAllowedError,
                                        final double defaultDynamicLambdaFactor)
            throws IllegalArgumentException {

        final Class<?> clazz;
        try {
            clazz = Class.forName(solveSetFactoryClassName);
        } catch (final ClassNotFoundException e) {
            throw new IllegalArgumentException("invalid solveSetFactoryClassName '" + solveSetFactoryClassName + "'",
                                               e);
        }

        final SolveSetFactory instance;
        if (SolveSetFactory.class.isAssignableFrom(clazz)) {
            final Constructor<?>[] ctors = clazz.getDeclaredConstructors();
            try {
                instance = (SolveSetFactory) ctors[0].newInstance(defaultGlobalSolveModel,
                                                                  defaultBlockSolveModel,
                                                                  defaultStitchingModel,
                                                                  defaultBlockOptimizerLambdasRigid,
                                                                  defaultBlockOptimizerLambdasTranslation,
                                                                  defaultBlockOptimizerIterations,
                                                                  defaultBlockMaxPlateauWidth,
                                                                  defaultMinStitchingInliers,
                                                                  defaultBlockMaxAllowedError,
                                                                  defaultDynamicLambdaFactor);
            } catch (final Exception e) {
                throw new IllegalArgumentException("failed to construct " + solveSetFactoryClassName + " instance",
                                                   e);
            }
        } else {
            throw new IllegalArgumentException(solveSetFactoryClassName + " must extend " + SolveSetFactory.class);
        }

        LOG.info("build: loaded instance of {}", instance.getClass());

        return instance;
    }

    private static final Logger LOG = LoggerFactory.getLogger(CustomSolveSetBuilder.class);
}

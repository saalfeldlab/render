package org.janelia.render.client.solver.custom;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import mpicbg.models.Affine2D;

import org.apache.commons.lang3.Range;
import org.janelia.render.client.solver.SolveItemData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

        for ( int z = firstProblemAreaZ - exclusionMargin; z<= firstProblemAreaZ + exclusionMargin; ++z )
        	additionalIssues.put(z, "problem with scan correction" );

        for ( int z = secondProblemAreaZ - exclusionMargin; z<= secondProblemAreaZ + exclusionMargin; ++z )
        	additionalIssues.put(z, "problem with scan correction" );
}

    @Override
	protected void addSolveItemDataToSets(final int setMinZ, final int setMaxZ,
			final Map<Integer, String> zToGroupIdMap, final int id,
			final List<SolveItemData<? extends Affine2D<?>, ? extends Affine2D<?>, ? extends Affine2D<?>>> sets) {

		boolean rigidPreAlign = false;

		List<Double> blockOptimizerLambdasRigid = defaultBlockOptimizerLambdasRigid;
		List<Double> blockOptimizerLambdasTranslation = defaultBlockOptimizerLambdasTranslation;
		List<Integer> blockOptimizerIterations = defaultBlockOptimizerIterations;
		List<Integer> blockMaxPlateauWidth = defaultBlockMaxPlateauWidth;

		if (containsIssue(setMinZ, setMaxZ, zToGroupIdMap, additionalIssues)) {
			// rigid alignment
			rigidPreAlign = true;

			// Do NOT DO allow rigid stitching
			//stitchingModel = ((InterpolatedAffineModel2D) stitchingModel ).copy();
			//((InterpolatedAffineModel2D) stitchingModel ).setLambda( 1.0 );
			
			// only rigid/affine solve, even more affine now
			blockOptimizerLambdasRigid = Stream.of(1.0, 0.9, 0.3, 0.1, 0.0).collect(Collectors.toList());
			blockOptimizerLambdasTranslation = Stream.of(0.0, 0.0, 0.0, 0.0, 0.0).collect(Collectors.toList());
			blockOptimizerIterations = Stream.of(2000, 500, 250, 250, 250).collect(Collectors.toList());
			blockMaxPlateauWidth = Stream.of(250, 150, 100, 100, 100).collect(Collectors.toList());

			LOG.info("addSolveItemDataToSets: set {}>>{} (index {}, id {}) contains issues, using rigid align", setMinZ,
					setMaxZ, sets.size(), id);
		}

		// set inlier count to default or to ridiculously high number for excluded layers
		// TODO: make also per layer
		final int minStitchingInliers = getMinStitchingInliers(setMinZ, setMaxZ);

		final Affine2D<?> stitchingModelf = defaultStitchingModel;

		sets.add(instantiateSolveItemData(id, this.defaultGlobalSolveModel, this.defaultBlockSolveModel,
				(Function<Integer, Affine2D<?>> & Serializable) (z) -> stitchingModelf, blockOptimizerLambdasRigid,
				blockOptimizerLambdasTranslation, blockOptimizerIterations, blockMaxPlateauWidth, minStitchingInliers,
				this.defaultBlockMaxAllowedError, this.defaultDynamicLambdaFactor, rigidPreAlign, setMinZ, setMaxZ));
	}

	private static final Logger LOG = LoggerFactory.getLogger(SolveSetFactoryOcellar.class);
}

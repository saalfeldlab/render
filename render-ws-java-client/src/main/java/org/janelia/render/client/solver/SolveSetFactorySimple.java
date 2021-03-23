package org.janelia.render.client.solver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import mpicbg.models.Affine2D;

public class SolveSetFactorySimple extends SolveSetFactory
{
	public SolveSetFactorySimple(
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
	}

	@Override
	public SolveSet defineSolveSet( final int minZ, final int maxZ, final int setSize, final Map<Integer, String> zToGroupIdMap )
	{
		final int modulo = ( maxZ - minZ + 1 ) % setSize;

		final int numSetsLeft = ( maxZ - minZ + 1 ) / setSize + Math.min( 1, modulo );

		final List< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > leftSets = new ArrayList<>();
		final List< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > rightSets = new ArrayList<>();

		int id = 0;

		for ( int i = 0; i < numSetsLeft; ++i )
		{
			leftSets.add(
					instantiateSolveItemData(
							id,
							this.defaultGlobalSolveModel,
							this.defaultBlockSolveModel,
							this.defaultStitchingModel,
							this.defaultBlockOptimizerLambdasRigid,
							this.defaultBlockOptimizerLambdasTranslation,
							this.defaultBlockOptimizerIterations,
							this.defaultBlockMaxPlateauWidth,
							this.defaultMinStitchingInliers,
							this.defaultBlockMaxAllowedError,
							this.defaultDynamicLambdaFactor,
							false,
							minZ + i * setSize,
							Math.min( minZ + (i + 1) * setSize - 1, maxZ ) ) );
			++id;
		}

		for ( int i = 0; i < numSetsLeft - 1; ++i )
		{
			final SolveItemData< ?, ?, ? > set0 = leftSets.get( i );
			final SolveItemData< ?, ?, ? > set1 = leftSets.get( i + 1 );

			rightSets.add(
					instantiateSolveItemData(
							id,
							this.defaultGlobalSolveModel,
							this.defaultBlockSolveModel,
							this.defaultStitchingModel,
							this.defaultBlockOptimizerLambdasRigid,
							this.defaultBlockOptimizerLambdasTranslation,
							this.defaultBlockOptimizerIterations,
							this.defaultBlockMaxPlateauWidth,
							this.defaultMinStitchingInliers,
							this.defaultBlockMaxAllowedError,
							this.defaultDynamicLambdaFactor,
							false,
							( set0.minZ() + set0.maxZ() ) / 2,
							( set1.minZ() + set1.maxZ() ) / 2 - 1 ) );
			++id;
		}

		return new SolveSet( leftSets, rightSets );
	}
}

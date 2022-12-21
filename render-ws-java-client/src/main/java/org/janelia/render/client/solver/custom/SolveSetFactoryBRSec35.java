package org.janelia.render.client.solver.custom;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.janelia.render.client.solver.SolveItemData;
import org.janelia.render.client.solver.SolveSet;
import org.janelia.render.client.solver.SolveSetFactory;
import org.janelia.render.client.solver.SolveSetFactoryAdaptiveRigid;
import org.janelia.render.client.solver.SolveSetFactory.SetInit;
import org.janelia.render.client.solver.SolveSetFactory.SetInit.Location;

import mpicbg.models.Affine2D;
import mpicbg.models.InterpolatedAffineModel2D;

public class SolveSetFactoryBRSec35 extends SolveSetFactoryAdaptiveRigid
{
	public HashMap<Integer, String> additionalIssues = new HashMap<>();

	/**
	 * @param defaultGlobalSolveModel - the default model for the final global solve (here always used)
	 * @param defaultBlockSolveModel - the default model (if layer contains no 'restart' or 'problem' tag), otherwise using less stringent model
	 * @param defaultStitchingModel - the default model when stitching per z slice (here always used)
	 * @param defaultBlockOptimizerLambdasRigid - the default rigid/affine lambdas for a block (from parameters)
	 * @param defaultBlockOptimizerLambdasTranslation - the default translation lambdas for a block (from parameters)
	 * @param defaultBlockOptimizerIterations - the default iterations (from parameters)
	 * @param defaultBlockMaxPlateauWidth - the default plateau with (from parameters)
	 * @param defaultMinStitchingInliers - how many inliers per tile pair are necessary for "stitching first"
	 * @param defaultBlockMaxAllowedError - the default max error for global opt (from parameters)
	 * @param defaultDynamicLambdaFactor - the default dynamic lambda factor
	 */
	public SolveSetFactoryBRSec35(
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

		for ( int i = 14445; i <= 15445; ++i )
			this.additionalIssues.put( i, "problem" );
	}

	@Override
	public SolveSet defineSolveSet( final int minZ, final int maxZ, final int blockSize, final int minBlockSize, final Map<Integer, String> zToGroupIdMap )
	{
		// left/right set init
		final List< SetInit > initSets = SolveSetFactory.defineSolveSetLayout( minZ, maxZ, blockSize, minBlockSize );

		final List< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > leftSets = new ArrayList<>();
		final List< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > rightSets = new ArrayList<>();

		for ( final SetInit initSet : initSets )
		{
			boolean rigidPreAlign = false;
			Affine2D< ? > stitchingModel = defaultStitchingModel;
			int minStitchingInliers = defaultMinStitchingInliers;
			List<Double> blockOptimizerLambdasRigid = defaultBlockOptimizerLambdasRigid;
			List<Double> blockOptimizerLambdasTranslation = defaultBlockOptimizerLambdasTranslation;
			List<Integer> blockOptimizerIterations = defaultBlockOptimizerIterations;
			List<Integer> blockMaxPlateauWidth = defaultBlockMaxPlateauWidth;

			if ( containsIssue( initSet.minZ(), initSet.maxZ(), zToGroupIdMap, additionalIssues ) )
			{
				// rigid alignment
				rigidPreAlign = true;

				// allow rigid stitching
				stitchingModel = ((InterpolatedAffineModel2D) stitchingModel ).copy();
				((InterpolatedAffineModel2D) stitchingModel ).setLambda( 1.0 );
	
				// only rigid/affine solve
				blockOptimizerLambdasRigid = Stream.of( 1.0,0.9,0.3,0.01 ).collect(Collectors.toList());
				blockOptimizerLambdasTranslation = Stream.of( 0.0,0.0,0.0,0.0 ).collect(Collectors.toList());
				blockOptimizerIterations = Stream.of( 2000,500,250,250 ).collect(Collectors.toList());
				blockMaxPlateauWidth = Stream.of( 250,150,100,100 ).collect(Collectors.toList());

				System.out.println( "set " + initSet.minZ() + ">>" + initSet.maxZ() + " ("  + initSet.getId() + ") contains issues, using rigid align." );
			}

			// allow translation stitching obly
			stitchingModel = ((InterpolatedAffineModel2D) stitchingModel ).copy();
			((InterpolatedAffineModel2D) stitchingModel ).setLambda( 0.0 );

			final Affine2D<?> stitchingModelf = stitchingModel;

			SolveItemData<? extends Affine2D<?>, ? extends Affine2D<?>, ? extends Affine2D<?>> sid =
					instantiateSolveItemData(
							initSet.getId(),
							this.defaultGlobalSolveModel,
							this.defaultBlockSolveModel,
							(Function< Integer, Affine2D<?> > & Serializable )(z) -> stitchingModelf,
							blockOptimizerLambdasRigid,
							blockOptimizerLambdasTranslation,
							blockOptimizerIterations,
							blockMaxPlateauWidth,
							minStitchingInliers,
							this.defaultBlockMaxAllowedError,
							this.defaultDynamicLambdaFactor,
							rigidPreAlign,
							initSet.minZ(),
							initSet.maxZ() );

			if ( initSet.location() == Location.LEFT )
				leftSets.add( sid );
			else
				rightSets.add( sid );
		}

		return new SolveSet( leftSets, rightSets );
	}

	protected static boolean containsIssue(
			final int min,
			final int max,
			final Map<Integer, String> zToGroupIdMap,
			final Map<Integer, String> additionalIssues )
	{
		for ( int i = min; i <= max; ++i )
			if ( zToGroupIdMap.containsKey( i ) || additionalIssues.containsKey( i ) )
				return true;

		return false;
	}
}

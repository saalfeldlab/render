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

import mpicbg.models.Affine2D;
import mpicbg.models.InterpolatedAffineModel2D;

public class SolveSetFactoryBRSec30 extends SolveSetFactory
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
	public SolveSetFactoryBRSec30(
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

		//4077-4416, 4781, 5020, 5552, 5669, 6010, 6489, 13078-13673, 20401-20888, 38439-39177
		for ( int i = 4077; i <= 4416; ++i )
			this.additionalIssues.put( i, "problem" );

		for ( int i = 13078; i <= 13673; ++i )
			this.additionalIssues.put( i, "problem" );

		for ( int i = 20401; i <= 20888; ++i )
			this.additionalIssues.put( i, "problem" );

		for ( int i = 38439; i <= 39177; ++i )
			this.additionalIssues.put( i, "problem" );

		this.additionalIssues.put( 4781, "problem" );
		this.additionalIssues.put( 5020, "problem" );
		this.additionalIssues.put( 5552, "problem" );
		this.additionalIssues.put( 5669, "problem" );
		this.additionalIssues.put( 6010, "problem" );
		this.additionalIssues.put( 6489, "problem" );
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
			final int setMinZ = minZ + i * setSize;
			final int setMaxZ = Math.min( minZ + (i + 1) * setSize - 1, maxZ );

			boolean rigidPreAlign = false;
			Affine2D< ? > stitchingModel = defaultStitchingModel;
			int minStitchingInliers = defaultMinStitchingInliers;
			List<Double> blockOptimizerLambdasRigid = defaultBlockOptimizerLambdasRigid;
			List<Double> blockOptimizerLambdasTranslation = defaultBlockOptimizerLambdasTranslation;
			List<Integer> blockOptimizerIterations = defaultBlockOptimizerIterations;
			List<Integer> blockMaxPlateauWidth = defaultBlockMaxPlateauWidth;

			if ( containsIssue( setMinZ, setMaxZ, zToGroupIdMap, additionalIssues ) )
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

				System.out.println( "set " + setMinZ + ">>" + setMaxZ + " ("  + i + ") contains issues, using rigid align." );
			}

			// allow translation stitching obly
			stitchingModel = ((InterpolatedAffineModel2D) stitchingModel ).copy();
			((InterpolatedAffineModel2D) stitchingModel ).setLambda( 0.0 );

			final Affine2D<?> stitchingModelf = stitchingModel;

			leftSets.add(
					instantiateSolveItemData(
							id,
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
							setMinZ,
							setMaxZ ) );
			++id;
		}

		for ( int i = 0; i < numSetsLeft - 1; ++i )
		{
			final SolveItemData< ?, ?, ? > set0 = leftSets.get( i );
			final SolveItemData< ?, ?, ? > set1 = leftSets.get( i + 1 );

			final int setMinZ = ( set0.minZ() + set0.maxZ() ) / 2;
			final int setMaxZ = ( set1.minZ() + set1.maxZ() ) / 2 - 1;

			boolean rigidPreAlign = false;
			Affine2D< ? > stitchingModel = defaultStitchingModel;
			int minStitchingInliers = defaultMinStitchingInliers;
			List<Double> blockOptimizerLambdasRigid = defaultBlockOptimizerLambdasRigid;
			List<Double> blockOptimizerLambdasTranslation = defaultBlockOptimizerLambdasTranslation;
			List<Integer> blockOptimizerIterations = defaultBlockOptimizerIterations;
			List<Integer> blockMaxPlateauWidth = defaultBlockMaxPlateauWidth;

			if ( containsIssue( setMinZ, setMaxZ, zToGroupIdMap, additionalIssues ) )
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

				System.out.println( "set " + setMinZ + ">>" + setMaxZ + " ("  + i + ") contains issues, using rigid align." );
			}

			// allow translation stitching obly
			stitchingModel = ((InterpolatedAffineModel2D) stitchingModel ).copy();
			((InterpolatedAffineModel2D) stitchingModel ).setLambda( 0.0 );

			final Affine2D<?> stitchingModelf = stitchingModel;

			rightSets.add(
					instantiateSolveItemData(
							id,
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
							setMinZ,
							setMaxZ ) );
			++id;
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

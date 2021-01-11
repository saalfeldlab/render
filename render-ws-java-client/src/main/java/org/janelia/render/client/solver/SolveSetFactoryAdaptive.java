package org.janelia.render.client.solver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import mpicbg.models.Affine2D;

public class SolveSetFactoryAdaptive extends SolveSetFactory
{
	final boolean constantSetSize;

	/**
	 * 
	 * @param defaultGlobalSolveModel - the default model for the final global solve (here always used)
	 * @param defaultBlockSolveModel - the default model (if layer contains no 'restart' or 'problem' tag), otherwise using less stringent model
	 * @param defaultStitchingModel - the default model when stitching per z slice (here always used)
	 * @param constantSetSize - if true assign the set size as requested (e.g. 500), and simply use
	 */
	public SolveSetFactoryAdaptive(
			final Affine2D<?> defaultGlobalSolveModel,
			final Affine2D<?> defaultBlockSolveModel,
			final Affine2D<?> defaultStitchingModel,
			final boolean constantSetSize )
	{
		super( defaultGlobalSolveModel, defaultBlockSolveModel, defaultStitchingModel );

		this.constantSetSize = constantSetSize;
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

			if ( containsIssue( setMinZ, setMaxZ, zToGroupIdMap) )
				System.out.println( "set " + setMinZ + ">>" + setMaxZ + " contains issues." );

			leftSets.add(
					instantiateSolveItemData(
							id,
							this.defaultGlobalSolveModel,
							this.defaultBlockSolveModel,
							this.defaultStitchingModel,
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

			if ( containsIssue( setMinZ, setMaxZ, zToGroupIdMap) )
				System.out.println( "set " + setMinZ + ">>" + setMaxZ + " contains issues." );

			rightSets.add(
					instantiateSolveItemData(
							id,
							this.defaultGlobalSolveModel,
							this.defaultBlockSolveModel,
							this.defaultStitchingModel,
							setMinZ,
							setMaxZ ) );
			++id;
		}

		return new SolveSet( leftSets, rightSets );
	}

	protected static boolean containsIssue( final int min, final int max, final Map<Integer, String> zToGroupIdMap )
	{
		for ( int i = min; i <= max; ++i )
			if ( zToGroupIdMap.containsKey( i ) )
				return true;

		return false;
	}
}

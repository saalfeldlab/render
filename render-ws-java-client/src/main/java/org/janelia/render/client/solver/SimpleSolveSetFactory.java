package org.janelia.render.client.solver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import mpicbg.models.Affine2D;
import mpicbg.models.Model;

public class SimpleSolveSetFactory extends SolveSetFactory
{
	public SimpleSolveSetFactory(
			final Affine2D<?> defaultGlobalSolveModel,
			final Affine2D<?> defaultBlockSolveModel,
			final Affine2D<?> defaultStitchingModel)
	{
		super( defaultGlobalSolveModel, defaultBlockSolveModel, defaultStitchingModel );
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
							( set0.minZ() + set0.maxZ() ) / 2,
							( set1.minZ() + set1.maxZ() ) / 2 - 1 ) );
			++id;
		}

		return new SolveSet( leftSets, rightSets );
	}

	@SuppressWarnings("unchecked")
	protected static < G extends Model< G > & Affine2D< G >, B extends Model< B > & Affine2D< B >, S extends Model< S > & Affine2D< S > > SolveItemData< G, B, S > instantiateSolveItemData(
			final int id,
			final Affine2D<?> globalSolveModel,
			final Affine2D<?> blockSolveModel,
			final Affine2D<?> stitchingModel,
			final int minZ,
			final int maxZ
			)
	{
		// it will crash here if the models are not Affine2D AND Model
		return new SolveItemData< G, B, S >( id, (G)(Object)globalSolveModel, (B)(Object)blockSolveModel, (S)(Object)stitchingModel, minZ, maxZ );
	}
}

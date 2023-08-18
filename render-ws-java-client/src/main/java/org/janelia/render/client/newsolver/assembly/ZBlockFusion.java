package org.janelia.render.client.newsolver.assembly;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blockfactories.ZBlockFactory;
import org.janelia.render.client.solver.DummySolveItemData;
import org.janelia.render.client.solver.SolveItemData;
import org.janelia.render.client.solver.SolveTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.Model;
import net.imglib2.util.Pair;

public class ZBlockFusion< Z, G extends Model< G >, R > implements BlockFusion< Z, G, R, ZBlockFactory >
{

	@Override
	public void globalFusion(
			final List<? extends BlockData<?, R, ?, ZBlockFactory>> blocks,
			final AssemblyMaps<Z> am)
	{
		/*
		final HashMap< BlockData<?, R, ?, ZBlockFactory>, Z > blockToResult = new HashMap<>();
		
		for ( final SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > solveItem : blockToTile.keySet() )
		{
			blockToResult.put( solveItem, SolveTools.createAffine( solveItemDataToTile.get( solveItem ).getModel() ) );

			if ( !DummySolveItemData.class.isInstance( solveItem ) )
				LOG.info( "Block " + solveItem.getId() + ": " + blockToAffine2d.get( solveItem ) );
		}

		final ArrayList< Integer > zSections = new ArrayList<>( gs.zToTileIdGlobal.keySet() );
		Collections.sort( zSections );

		for ( final int z : zSections )
		{
			// for every z section, tileIds might be provided from different overlapping blocks if they were not connected and have been split
			final ArrayList< Pair< Pair< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > >, SolveItemData < ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > >>, HashSet< String > > > entries = zToSolveItemPairs.get( z );

			for ( final Pair< Pair< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > >, SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > >, HashSet< String > > entry : entries )
			{
				for ( final String tileId : entry.getB() )
				{
					final Pair< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > >, SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > solveItemPair =
							entry.getA();

					final SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > solveItemA = solveItemPair.getA();
					final SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > solveItemB = solveItemPair.getB();

					final AffineModel2D modelA = solveItemA.idToNewModel().get( tileId );
					final AffineModel2D modelB = solveItemB.idToNewModel().get( tileId );

					final AffineModel2D globalModelA = blockToResult.get( solveItemA );
					modelA.preConcatenate( globalModelA );

					final AffineModel2D globalModelB = blockToResult.get( solveItemB );
					modelB.preConcatenate( globalModelB );

					final double wA = solveItemA.getWeight( z );
					final double wB = solveItemB.getWeight( z );

					// if one of them is zero the model stays at it is
					final double regularizeB, dynamicLambda;
					final AffineModel2D tileModel;

					if ( wA == 0 && wB == 0 )
						throw new RuntimeException( "Two block with weight 0, this must not happen: " + solveItemA.getId() + ", " + solveItemB.getId() );
					else if ( wA == 0 )
					{
						tileModel = modelB.copy();
						regularizeB = 1;
						dynamicLambda = solveItemB.zToDynamicLambda().get( z );
					}
					else if ( wB == 0 )
					{
						tileModel = modelA.copy();
						regularizeB = 0;
						dynamicLambda = solveItemA.zToDynamicLambda().get( z );
					}
					else
					{
						regularizeB = wB / (wA + wB);
						tileModel = new InterpolatedAffineModel2D<>( modelA, modelB, regularizeB ).createAffineModel2D();
						dynamicLambda = solveItemA.zToDynamicLambda().get( z ) *  (1 - regularizeB) + solveItemB.zToDynamicLambda().get( z ) * regularizeB;
					}

					LOG.info( "z=" + z + ": " + solveItemA.getId() + "-" + wA + " ----- " + solveItemB.getId() + "-" + wB + " ----regB=" + regularizeB );

					gs.zToDynamicLambdaGlobal.put( z, dynamicLambda );
					gs.idToFinalModelGlobal.put( tileId, tileModel );

					// TODO: proper error computation using the matches that are now stored in the SolveItemData object
					if ( regularizeB < 0.5 )
						gs.idToErrorMapGlobal.put( tileId, solveItemA.idToSolveItemErrorMap.get( tileId ) );
					else
						gs.idToErrorMapGlobal.put( tileId, solveItemB.idToSolveItemErrorMap.get( tileId ) );
				}
			}
		}*/

	}

	private static final Logger LOG = LoggerFactory.getLogger(ZBlockFusion.class);
}

package org.janelia.render.client.newsolver.assembly;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blockfactories.ZBlockFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mpicbg.models.Model;
import mpicbg.models.Tile;
import net.imglib2.util.Pair;

public class ZBlockFusion< Z, I, G extends Model< G >, R > implements BlockFusion< Z, G, R, ZBlockFactory >
{
	final ZBlockSolver<Z, G, R> solver;
	final BiFunction< R, G, I > combineResultGlobal;
	final BiFunction< List< I >, List< Double >, Z > fusion;

	public ZBlockFusion(
			final ZBlockSolver< Z, G, R > solver,
			final BiFunction< R, G, I > combineResultGlobal, // I is some intermediate (maybe R, maybe something else)
			final BiFunction< List< I >, List< Double >, Z > fusion ) // then fuse many weighted I's into Z's
	{
		this.solver = solver;
		this.combineResultGlobal = combineResultGlobal;
		this.fusion = fusion;
	}

	@Override
	public void globalFusion(
			final List<? extends BlockData<?, R, ?, ZBlockFactory>> blocks,
			final AssemblyMaps<Z> am, 
			final HashMap< BlockData<?, R, ?, ZBlockFactory >, Tile< G > > blockToTile )
	{
		final HashMap< BlockData<?, R, ?, ZBlockFactory>, G > blockToG = new HashMap<>();

		for ( final BlockData<?, R, ?, ZBlockFactory > solveItem : blockToTile.keySet() )
		{
			if ( solveItem != null )
			{
				blockToG.put( solveItem, blockToTile.get( solveItem ).getModel() );//SolveTools.createAffine( blockToTile.get( solveItem ).getModel() ) );
				LOG.info( "Block " + solveItem.getId() + ": " + blockToG.get( solveItem ) );
			}

			//if ( !DummySolveItemData.class.isInstance( solveItem ) )
			//	LOG.info( "Block " + solveItem.getId() + ": " + blockToZ.get( solveItem ) );
		}

		final ArrayList< Integer > zSections = new ArrayList<>( am.zToTileIdGlobal.keySet() );
		Collections.sort( zSections );

		final HashMap< BlockData<?, R, ?, ZBlockFactory>, ArrayList< Function< Double, Double > > > blockToWeightfunctions =
				new HashMap<>();

		for ( final int z : zSections )
		{
			// for every z section, tileIds might be provided from different overlapping blocks if they were not connected and have been split
			final ArrayList< Pair< Pair< BlockData<?, R, ?, ZBlockFactory>, BlockData<?, R, ?, ZBlockFactory>>, HashSet< String > > > entries =
					solver.zToBlockPairs.get( z );

			for ( final Pair< Pair< BlockData<?, R, ?, ZBlockFactory>, BlockData<?, R, ?, ZBlockFactory>>, HashSet< String > > entry : entries )
			{
				for ( final String tileId : entry.getB() )
				{
					final Pair< BlockData<?, R, ?, ZBlockFactory>, BlockData<?, R, ?, ZBlockFactory>> solveItemPair = entry.getA();

					final BlockData<?, R, ?, ZBlockFactory> solveItemA = solveItemPair.getA();
					final BlockData<?, R, ?, ZBlockFactory> solveItemB = solveItemPair.getB();

					// one of them can be null (beginning and end of stack)
					// just the weight functions are actually important here!
					final ArrayList< Function< Double, Double > > wfA, wfB;
					final R modelAIn, modelBIn;
					final G globalModelA, globalModelB;
					final int idA, idB;

					if ( solveItemA == null )
					{
						wfA = emptyWeightFunction( 3 );
						wfB = blockToWeightfunctions.computeIfAbsent( solveItemB, s -> s.createWeightFunctions() );

						modelAIn = solveItemB.idToNewModel().get( tileId );
						modelBIn = solveItemB.idToNewModel().get( tileId );

						globalModelA = blockToG.get( solveItemB );
						globalModelB = blockToG.get( solveItemB );

						idA = -1;
						idB = solveItemB.getId();
					}
					else if ( solveItemB == null )
					{
						wfA = blockToWeightfunctions.computeIfAbsent( solveItemA, s -> s.createWeightFunctions() );
						wfB = emptyWeightFunction( 3 );

						modelAIn = solveItemA.idToNewModel().get( tileId );
						modelBIn = solveItemA.idToNewModel().get( tileId );

						globalModelA = blockToG.get( solveItemA );
						globalModelB = blockToG.get( solveItemA );

						idA = solveItemA.getId();
						idB = -1;
					}
					else
					{
						wfA = blockToWeightfunctions.computeIfAbsent( solveItemA, s -> s.createWeightFunctions() );
						wfB = blockToWeightfunctions.computeIfAbsent( solveItemB, s -> s.createWeightFunctions() );

						modelAIn = solveItemA.idToNewModel().get( tileId );
						modelBIn = solveItemB.idToNewModel().get( tileId );

						globalModelA = blockToG.get( solveItemA );
						globalModelB = blockToG.get( solveItemB );

						idA = solveItemA.getId();
						idB = solveItemB.getId();
					}

					//final R modelAIn = solveItemA.idToNewModel().get( tileId );
					//final R modelBIn = solveItemB.idToNewModel().get( tileId );

					//final G globalModelA = blockToG.get( solveItemA );
					//modelA.preConcatenate( globalModelA );
					final I modelA = combineResultGlobal.apply( modelAIn, globalModelA );

					//final G globalModelB = blockToG.get( solveItemB );
					//modelB.preConcatenate( globalModelB );
					final I modelB = combineResultGlobal.apply( modelBIn, globalModelB );

					// TODO: very inefficient to create the weight functions on the fly
					final double wA = wfA.get( 2 ).apply( (double)z );// .getWeight( z );
					final double wB = wfB.get( 2 ).apply( (double)z );//.getWeight( z );

					// if one of them is zero the model stays at it is
					final double regularizeB;
					//final double dynamicLambda;
					//final AffineModel2D tileModel;
					final Z tileModel;

					if ( wA == 0 && wB == 0 )
						throw new RuntimeException( "Two block with weight 0, this must not happen: " + idA + ", " + idB );
					/*else if ( wA == 0 )
					{
						tileModel = modelB.copy();
						regularizeB = 1;
						//dynamicLambda = solveItemB.zToDynamicLambda().get( z );
					}
					else if ( wB == 0 )
					{
						tileModel = modelA.copy();
						regularizeB = 0;
						//dynamicLambda = solveItemA.zToDynamicLambda().get( z );
					}*/
					else
					{
						regularizeB = wB / (wA + wB);

						tileModel = fusion.apply(
								new ArrayList<>( Arrays.asList( modelA, modelB ) ),
								new ArrayList<>( Arrays.asList( 1.0 - regularizeB, regularizeB ) ) );

						//tileModel = new InterpolatedAffineModel2D<>( modelA, modelB, regularizeB ).createAffineModel2D();
						//dynamicLambda = solveItemA.zToDynamicLambda().get( z ) *  (1 - regularizeB) + solveItemB.zToDynamicLambda().get( z ) * regularizeB;
					}

					LOG.info( "z=" + z + ": " + idA + "-" + wA + " ----- " + idB + "-" + wB + " ----regB=" + regularizeB );

					//gs.zToDynamicLambdaGlobal.put( z, dynamicLambda );
					am.idToFinalModelGlobal.put( tileId, tileModel );

					// TODO: proper error computation using the matches that are now stored in the SolveItemData object
					// works, because a null solveItem always has a weight of 0
					if ( regularizeB < 0.5 )
						am.idToErrorMapGlobal.put( tileId, solveItemA.idToBlockErrorMap().get( tileId ) );
					else
						am.idToErrorMapGlobal.put( tileId, solveItemB.idToBlockErrorMap().get( tileId ) );
				}
			}
		}
	}

	public static ArrayList< Function< Double, Double > > emptyWeightFunction( final int dim )
	{
		final ArrayList< Function< Double, Double > > weightF = new ArrayList<>();

		for ( int d = 0; d < dim; ++d )
			weightF.add( m -> 0.0 );

		return weightF;
	}

	private static final Logger LOG = LoggerFactory.getLogger(ZBlockFusion.class);
}

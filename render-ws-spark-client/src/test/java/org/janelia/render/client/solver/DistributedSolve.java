package org.janelia.render.client.solver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.ClientRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ij.ImageJ;
import ij.ImagePlus;
import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TileUtil;
import mpicbg.models.TranslationModel2D;
import mpicbg.spim.io.IOFunctions;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;

public abstract class DistributedSolve< G extends Model< G > & Affine2D< G >, B extends Model< B > & Affine2D< B >, S extends Model< S > & Affine2D< S > >
{
	final ParametersDistributedSolve parameters;
	final RunParameters runParams;

//	final public static double maxAllowedError = 10;
//	final public static int numIterations = 1000;
//	final public static int maxPlateauWidth = 500;

	public static class GlobalSolve
	{
		final HashMap<String, AffineModel2D> idToFinalModelGlobal = new HashMap<>();
		final HashMap<String, MinimalTileSpec> idToTileSpecGlobal = new HashMap<>();
		final HashMap<Integer, HashSet<String> > zToTileIdGlobal = new HashMap<>();
	}

	final G globalSolveModel;
	final B blockSolveModel;
	final S stitchingModel;

	final SolveSet< G, B, S > solveSet;

	public DistributedSolve(
			final G globalSolveModel,
			final B blockSolveModel,
			final S stitchingModel,
			final ParametersDistributedSolve parameters ) throws IOException
	{
		this.parameters = parameters;
		this.runParams = ParametersDistributedSolve.setupSolve( parameters );

		this.globalSolveModel = globalSolveModel;
		this.blockSolveModel = blockSolveModel;
		this.stitchingModel = stitchingModel;

		// TODO: load matches only once, not for each thread
		// assembleMatchData( parameters, runParams );

		final int minZ = (int)Math.round( this.runParams.minZ );
		final int maxZ = (int)Math.round( this.runParams.maxZ );

		this.solveSet = defineSolveSet( minZ, maxZ, parameters.blockSize );

		LOG.info( "Defined sets for global solve" );
		LOG.info( "\n" + solveSet );
	}

	public abstract void run();

	protected static HashSet< String > commonStrings( final HashSet< String > tileIdsA, final HashSet< String > tileIdsB )
	{
		final HashSet< String > commonStrings = new HashSet<>();

		for ( final String a : tileIdsA )
			if ( tileIdsB.contains( a ) )
				commonStrings.add( a );

		return commonStrings;
	}

	protected boolean pairExists(
			final int z,
			final SolveItemData< G, B, S > solveItemA,
			final SolveItemData< G, B, S > solveItemB,
			final HashMap<Integer, ArrayList< Pair< Pair< SolveItemData< G, B, S >, SolveItemData< G, B, S > >, HashSet< String > > > > zToSolveItemPairs )
	{
		if ( zToSolveItemPairs.containsKey( z ) )
		{
			final ArrayList< Pair< Pair< SolveItemData< G, B, S >, SolveItemData< G, B, S > >, HashSet< String > > > entries = zToSolveItemPairs.get( z );

			for ( final Pair< Pair< SolveItemData< G, B, S >, SolveItemData< G, B, S > >, HashSet< String > > entry : entries )
				if (entry.getA().getA().equals( solveItemA ) && entry.getA().getB().equals( solveItemB ) ||
					entry.getA().getA().equals( solveItemB ) && entry.getA().getB().equals( solveItemA ) )
						return true;

			return false;
		}
		else
		{
			return false;
		}
	}

	protected GlobalSolve globalSolve( final List< SolveItemData< G, B, S > > allSolveItems ) throws NotEnoughDataPointsException, IllDefinedDataPointsException, InterruptedException, ExecutionException, NoninvertibleModelException
	{
		final GlobalSolve gs = new GlobalSolve();

		// local structures required for solvig
		final HashMap<Integer, ArrayList< Pair< Pair< SolveItemData< G, B, S >, SolveItemData< G, B, S > >, HashSet< String > > > > zToSolveItemPairs = new HashMap<>();
		final TileConfiguration tileConfigBlocks = new TileConfiguration();

		final HashMap< SolveItemData< G, B, S >, Tile< G > > solveItemDataToTile = new HashMap<>();

		// important: all images within one solveitem must be connected to each other!

		// solve by solveitem, not by z layer
		for ( int a = 0; a < allSolveItems.size() - 1; ++a )
		{
			final SolveItemData< G, B, S > solveItemA = allSolveItems.get( a );
			solveItemDataToTile.putIfAbsent( solveItemA, new Tile<>( solveItemA.globalSolveModelInstance() ) );

			for ( int z = solveItemA.minZ(); z <= solveItemA.maxZ(); ++z )
			{
				// is this zPlane overlapping with anything?
				boolean hasOverlap = false;

				for ( int b = a + 1; b < allSolveItems.size(); ++b )
				{
					final SolveItemData< G, B, S > solveItemB = allSolveItems.get( b );
					solveItemDataToTile.putIfAbsent( solveItemB, new Tile<>( solveItemB.globalSolveModelInstance() ) );

					if ( solveItemA.equals( solveItemB ) )
						continue;

					// is overlapping
					if ( z >= solveItemB.minZ() && z <= solveItemB.maxZ() )
					{
						// every pair exists twice
						if ( pairExists( z, solveItemA, solveItemB, zToSolveItemPairs ) )
							continue;

						// get tileIds for each z section (they might only be overlapping)
						final HashSet< String > tileIdsA = solveItemA.zToTileId().get( z );
						final HashSet< String > tileIdsB = solveItemB.zToTileId().get( z );

						// if a section is not present
						if ( tileIdsA == null || tileIdsB == null )
							continue;

						// which tileIds are the same between solveItemA and solveItemB
						final HashSet< String > tileIds = commonStrings( tileIdsA, tileIdsB );

						// if there are none, we continue with the next
						if ( tileIds.size() == 0 )
							continue;

						gs.zToTileIdGlobal.putIfAbsent( z, new HashSet<>() );
						zToSolveItemPairs.putIfAbsent( z, new ArrayList<>() );

						// remember which solveItems defined which tileIds of this z section
						zToSolveItemPairs.get( z ).add( new ValuePair<>( new ValuePair<>( solveItemA, solveItemB ), tileIds ) );

						final List< PointMatch > matchesAtoB = new ArrayList<>();

						for ( final String tileId : tileIds )
						{
							// tilespec is identical
							final MinimalTileSpec tileSpec = solveItemA.idToTileSpec().get( tileId );

							// remember the tileids and tileSpecs
							gs.zToTileIdGlobal.get( z ).add( tileId );
							gs.idToTileSpecGlobal.put( tileId, tileSpec );

							final AffineModel2D modelA = solveItemA.idToNewModel().get( tileId );
							final AffineModel2D modelB = solveItemB.idToNewModel().get( tileId );

							// make a regular grid
							final double sampleWidth = (tileSpec.getWidth() - 1.0) / (SolveItem.samplesPerDimension - 1.0);
							final double sampleHeight = (tileSpec.getHeight() - 1.0) / (SolveItem.samplesPerDimension - 1.0);

							for (int y = 0; y < SolveItem.samplesPerDimension; ++y)
							{
								final double sampleY = y * sampleHeight;
								for (int x = 0; x < SolveItem.samplesPerDimension; ++x)
								{
									final double[] p = new double[] { x * sampleWidth, sampleY };
									final double[] q = new double[] { x * sampleWidth, sampleY };

									modelA.applyInPlace( p );
									modelB.applyInPlace( q );

									matchesAtoB.add(new PointMatch( new Point(p), new Point(q) ));
								}
							}
						}

						final Tile< G > tileA = solveItemDataToTile.get( solveItemA );
						final Tile< G > tileB = solveItemDataToTile.get( solveItemB );

						tileA.connect( tileB, matchesAtoB );

						tileConfigBlocks.addTile( tileA );
						tileConfigBlocks.addTile( tileB );

						hasOverlap = true;
					}
				}

				if ( !hasOverlap )
				{
					// there is no overlap with any other solveItem (should be beginning or end of the entire stack)
					final HashSet< String > tileIds = solveItemA.zToTileId().get( z );
					
					// if there are none, we continue with the next
					if ( tileIds.size() == 0 )
						continue;

					gs.zToTileIdGlobal.putIfAbsent( z, new HashSet<>() );
					zToSolveItemPairs.putIfAbsent( z, new ArrayList<>() );

					// remember which solveItems defined which tileIds of this z section
					
					final SolveItemData< G, B, S > solveItemB = new DummySolveItemData< G, B, S >( solveItemA.globalSolveModelInstance(), solveItemA.blockSolveModelInstance(), solveItemA.stitchingSolveModelInstance(), z );
					zToSolveItemPairs.get( z ).add( new ValuePair<>( new ValuePair<>( solveItemA, solveItemB ), tileIds ) );
					solveItemDataToTile.putIfAbsent( solveItemB, new Tile<>( solveItemB.globalSolveModelInstance() ) );

					for ( final String tileId : tileIds )
					{
						solveItemB.idToNewModel().put( tileId, new AffineModel2D() );

						// remember the tileids and tileSpecs
						gs.zToTileIdGlobal.get( z ).add( tileId );
						gs.idToTileSpecGlobal.put( tileId, solveItemA.idToTileSpec().get( tileId ) );
					}
				}
			}
		}

		LOG.info( "Pre-Align ... " );

		tileConfigBlocks.preAlign();

		LOG.info( "Optimizing ... " );
		final float damp = 1.0f;
		TileUtil.optimizeConcurrently(
				new ErrorStatistic(parameters.maxPlateauWidthGlobal + 1 ),
				parameters.maxAllowedErrorGlobal,
				parameters.maxIterationsGlobal,
				parameters.maxPlateauWidthGlobal,
				damp,
				tileConfigBlocks,
				tileConfigBlocks.getTiles(),
				tileConfigBlocks.getFixedTiles(),
				parameters.threadsGlobal );

		final HashMap< SolveItemData< G, B, S >, AffineModel2D > blockToAffine2d = new HashMap<>();
	
		for ( final SolveItemData< G, B, S > solveItem : solveItemDataToTile.keySet() )
		{
			blockToAffine2d.put( solveItem, DistributedSolveWorker.createAffine( solveItemDataToTile.get( solveItem ).getModel() ) );

			if ( !DummySolveItemData.class.isInstance( solveItem ) )
				LOG.info( "Block " + solveItem.getId() + ": " + blockToAffine2d.get( solveItem ) );
		}

		final ArrayList< Integer > zSections = new ArrayList<>( gs.zToTileIdGlobal.keySet() );
		Collections.sort( zSections );

		for ( final int z : zSections )
		{
			// for every z section, tileIds might be provided from different overlapping blocks if they were not connected and have been split
			final ArrayList< Pair< Pair< SolveItemData< G, B, S >, SolveItemData < G, B, S >>, HashSet< String > > > entries = zToSolveItemPairs.get( z );

			for ( final Pair< Pair< SolveItemData< G, B, S >, SolveItemData< G, B, S > >, HashSet< String > > entry : entries )
			{
				for ( final String tileId : entry.getB() )
				{
					final Pair< SolveItemData< G, B, S >, SolveItemData< G, B, S > > solveItemPair = entry.getA();

					final SolveItemData< G, B, S > solveItemA = solveItemPair.getA();
					final SolveItemData< G, B, S > solveItemB = solveItemPair.getB();

					final AffineModel2D modelA = solveItemA.idToNewModel().get( tileId );
					final AffineModel2D modelB = solveItemB.idToNewModel().get( tileId );

					final AffineModel2D globalModelA = blockToAffine2d.get( solveItemA );
					modelA.preConcatenate( globalModelA );

					final AffineModel2D globalModelB = blockToAffine2d.get( solveItemB );
					modelB.preConcatenate( globalModelB );

					final double wA = solveItemA.getWeight( z );
					final double wB = solveItemB.getWeight( z );

					// if one of them is zero the model stays at it is
					final double regularizeB;
					final AffineModel2D tileModel;

					if ( wA == 0 && wB == 0 )
						throw new RuntimeException( "Two block with weight 0, this must not happen: " + solveItemA.getId() + ", " + solveItemB.getId() );
					else if ( wA == 0 )
					{
						tileModel = modelB.copy();
						regularizeB = 1;
					}
					else if ( wB == 0 )
					{
						tileModel = modelA.copy();
						regularizeB = 0;
					}
					else
					{
						regularizeB = wB / (wA + wB);
						tileModel = new InterpolatedAffineModel2D<>( modelA, modelB, regularizeB ).createAffineModel2D();
					}

					LOG.info( "z=" + z + ": " + solveItemA.getId() + "-" + wA + " ----- " + solveItemB.getId() + "-" + wB + " ----regB=" + regularizeB );


					gs.idToFinalModelGlobal.put( tileId, tileModel );
				}
			}
		}

		return gs;
	}

	protected SolveSet< G, B, S > defineSolveSet( final int minZ, final int maxZ, final int setSize )
	{
		final int modulo = ( maxZ - minZ + 1 ) % setSize;

		final int numSetsLeft = ( maxZ - minZ + 1 ) / setSize + Math.min( 1, modulo );

		final ArrayList< SolveItemData< G, B, S > > leftSets = new ArrayList<>();
		final ArrayList< SolveItemData< G, B, S > > rightSets = new ArrayList<>();

		for ( int i = 0; i < numSetsLeft; ++i )
		{
			leftSets.add(
					new SolveItemData< G, B, S >(
							this.globalSolveModel,
							this.blockSolveModel,
							this.stitchingModel,
							minZ + i * setSize,
							Math.min( minZ + (i + 1) * setSize - 1, maxZ ) ) );
		}

		for ( int i = 0; i < numSetsLeft - 1; ++i )
		{
			final SolveItemData< G, B, S > set0 = leftSets.get( i );
			final SolveItemData< G, B, S > set1 = leftSets.get( i + 1 );

			rightSets.add(
					new SolveItemData< G, B, S >(
							this.globalSolveModel,
							this.blockSolveModel,
							this.stitchingModel,
							( set0.minZ() + set0.maxZ() ) / 2,
							( set1.minZ() + set1.maxZ() ) / 2 ) );
		}

		return new SolveSet< G, B, S >( leftSets, rightSets );
	}

	private static final Logger LOG = LoggerFactory.getLogger(DistributedSolve.class);
}

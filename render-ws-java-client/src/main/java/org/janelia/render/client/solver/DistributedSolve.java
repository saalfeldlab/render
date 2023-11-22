package org.janelia.render.client.solver;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.janelia.alignment.match.Matches;
import org.janelia.alignment.spec.ResolvedTileSpecCollection.TransformApplicationMethod;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.solver.visualize.VisualizeTools;
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
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;

public abstract class DistributedSolve
{
	public static boolean visualizeOutput = false;
	public static int visMinZ = Integer.MIN_VALUE;
	public static int visMaxZ = Integer.MAX_VALUE;

	final DistributedSolveParameters parameters;
	final RunParameters runParams;

	public static class GlobalSolve
	{
		final HashMap<String, AffineModel2D> idToFinalModelGlobal = new HashMap<>();
		final HashMap<String, TileSpec> idToTileSpecGlobal = new HashMap<>();
		final HashMap<Integer, HashSet<String> > zToTileIdGlobal = new HashMap<>();
		final HashMap<Integer, Double> zToDynamicLambdaGlobal = new HashMap<>();
		final HashMap< String, List< Pair< String, Double > > > idToErrorMapGlobal = new HashMap<>();
	}

	final SolveSetFactory solveSetFactory;
	final SolveSet solveSet;
	DistributedSolveSerializer serializer = null;

	List< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > allItems = null;
	GlobalSolve solve = null;

	public DistributedSolve(
			final SolveSetFactory solveSetFactory,
			final DistributedSolveParameters parameters ) throws IOException
	{
		this.solveSetFactory = solveSetFactory;
		this.parameters = parameters;
		this.runParams = DistributedSolveParameters.setupSolve( parameters );

		// TODO: load matches only once, not for each thread
		// assembleMatchData( parameters, runParams );

		final int minZ = (int)Math.round( this.runParams.minZ );
		final int maxZ = (int)Math.round( this.runParams.maxZ );

		this.solveSet = solveSetFactory.defineSolveSet( minZ, maxZ, parameters.blockSize, parameters.minBlockSize, runParams.zToGroupIdMap );

		LOG.info( "Defined sets for global solve" );
		LOG.info( "\n" + solveSet );

		//System.exit( 0 );
		if (parameters.serializerDirectory != null) {
			this.serializer= new DistributedSolveSerializer( new File(parameters.serializerDirectory) );
		}
	}

	protected abstract List< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > distributedSolve();

	public void run() throws IOException, NoninvertibleModelException
	{
		try
		{
			this.allItems = distributedSolve();
		}
		catch ( Exception e )
		{
			LOG.info("FAILED to compute distributed blocks (STOPPING): " + e );
			e.printStackTrace();
			return;
		}

		// avoid duplicate id assigned while splitting solveitems in the workers
		// but do keep ids that are smaller or equal to the maxId of the initial solveset
		final int maxId = SolveTools.fixIds( this.allItems, solveSet.getMaxId() );

		try
		{
			if ( serializer != null )
				serializer.serialize( this.allItems );
		}
		catch ( Exception e )
		{
			LOG.info("FAILED to serialize (continuing): " + e );
			e.printStackTrace();
		}

		try
		{
			this.solve = globalSolve( this.allItems, maxId + 1 );
		}
		catch ( Exception e )
		{
			LOG.info("FAILED to compute global solve (STOPPING): " + e );
			e.printStackTrace();
			return;
		}

		try
		{
			computeGlobalErrors( this.solve, this.allItems, new InterpolatedAffineModel2D<>( new AffineModel2D(), new RigidModel2D(), 0.25 ), (Model<?>)solveSetFactory.defaultStitchingModel );
		}
		catch ( Exception e )
		{
			LOG.info("FAILED to compute global errors: " + e );
			e.printStackTrace();
			return;
		}

		if ( parameters.targetStack != null )
		{
			LOG.info( "Saving targetstack=" + parameters.targetStack );

			//
			// save the re-aligned part
			//
			final HashSet< Double > zToSaveSet = new HashSet<>();

			for (final TileSpec ts : solve.idToTileSpecGlobal.values())
				zToSaveSet.add( ts.getZ() );

			List< Double > zToSave = new ArrayList<>( zToSaveSet );
			Collections.sort( zToSave );

			LOG.info("Saving from " + zToSave.get( 0 ) + " to " + zToSave.get( zToSave.size() - 1 ) );

			SolveTools.saveTargetStackTiles( parameters.stack, parameters.targetStack, runParams, solve.idToFinalModelGlobal, null, zToSave, TransformApplicationMethod.REPLACE_LAST );

			if ( parameters.completeTargetStack )
			{
				LOG.info( "Completing targetstack=" + parameters.targetStack );

				SolveTools.completeStack( parameters.targetStack, runParams );
			}
		}

		if ( visualizeOutput )
		{
			// visualize new result
			new ImageJ();
			final ImagePlus imp = VisualizeTools.render( solve.idToFinalModelGlobal, solve.idToTileSpecGlobal, 0.15, visMinZ, visMaxZ );
			imp.setTitle( "final" );
			VisualizeTools.renderBDV( imp, 0.15 );
		}
	}

	public List< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > allItems() { return allItems; }
	public GlobalSolve globalSolve() { return solve; }

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
			final SolveItemData< ?, ?, ? > solveItemA,
			final SolveItemData< ?, ?, ? > solveItemB,
			final HashMap<Integer, ? extends ArrayList< ? extends Pair< ? extends Pair< ? extends SolveItemData< ?, ?, ? >, ? extends SolveItemData< ?, ?, ? > >, HashSet< String > > > > zToSolveItemPairs )
	{
		if ( zToSolveItemPairs.containsKey( z ) )
		{
			final ArrayList< ? extends Pair< ? extends Pair< ? extends SolveItemData< ?, ?, ? >, ? extends SolveItemData< ?, ?, ? > >, HashSet< String > > > entries = zToSolveItemPairs.get( z );

			for ( final Pair< ? extends Pair< ? extends SolveItemData< ?, ?, ? >, ? extends SolveItemData< ?, ?, ? > >, HashSet< String > > entry : entries )
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

	protected GlobalSolve globalSolve(
			final List< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > allSolveItems,
			final int startId )
					throws NotEnoughDataPointsException, IllDefinedDataPointsException, InterruptedException, ExecutionException, NoninvertibleModelException
	{
		int id = startId;

		final GlobalSolve gs = new GlobalSolve();

		// the trivial case, would crash with the code below
		if ( allSolveItems.size() == 1 )
		{
			LOG.info( "globalSolve: only a single solveitem, no solve across blocks necessary." );

			final SolveItemData<? extends Affine2D<?>, ? extends Affine2D<?>, ? extends Affine2D<?>> solveItem = allSolveItems.get( 0 );
	
			for ( int z = solveItem.minZ(); z <= solveItem.maxZ(); ++z )
			{
				// there is no overlap with any other solveItem (should be beginning or end of the entire stack)
				final HashSet< String > tileIds = solveItem.zToTileId().get( z );

				// if there are none, we continue with the next
				if ( tileIds.size() == 0 )
					continue;

				gs.zToTileIdGlobal.putIfAbsent( z, new HashSet<>() );

				for ( final String tileId : tileIds )
				{
					gs.zToTileIdGlobal.get( z ).add( tileId );
					gs.idToTileSpecGlobal.put( tileId, solveItem.idToTileSpec().get( tileId ) );
					gs.idToFinalModelGlobal.put( tileId, solveItem.idToNewModel().get( tileId ) );
					gs.zToDynamicLambdaGlobal.put( z, solveItem.zToDynamicLambda().get( z ) );
				}
			}

			return gs;
		}

		// local structures required for solvig
		final HashMap<
				Integer,
				ArrayList<
					Pair<
						Pair<
							SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > >,
							SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > >,
						HashSet< String > > > > zToSolveItemPairs = new HashMap<>();
		
		final TileConfiguration tileConfigBlocks = new TileConfiguration();

		final HashMap< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > >, Tile< ? extends Affine2D< ? > > > solveItemDataToTile = new HashMap<>();

		// important: all images within one solveitem must be connected to each other!

		LOG.info( "globalSolve: solving {} items", allSolveItems.size() );

		// solve by solveitem, not by z layer
		for ( int a = 0; a < allSolveItems.size() - 1; ++a )
		{
			final SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > solveItemA = allSolveItems.get( a );
			solveItemDataToTile.putIfAbsent( solveItemA, new Tile( solveItemA.globalSolveModelInstance() ) ); //TODO: how comes I can init a Tile with Affine2D???

			for ( int z = solveItemA.minZ(); z <= solveItemA.maxZ(); ++z )
			{
				LOG.info( "globalSolve: solveItemA z range is {} to {}", solveItemA.minZ(), solveItemA.maxZ());

				// is this zPlane of SolveItemA overlapping with anything?
				boolean wasAssigned = false;

				for ( int b = a + 1; b < allSolveItems.size(); ++b )
				{
					final SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > solveItemB = allSolveItems.get( b );
					solveItemDataToTile.putIfAbsent( solveItemB, new Tile( solveItemB.globalSolveModelInstance() ) );

					LOG.info( "globalSolve: solveItemB z range is {} to {}", solveItemB.minZ(), solveItemB.maxZ());

					if ( solveItemA.equals( solveItemB ) )
						continue;

					// is overlapping
					if ( z >= solveItemB.minZ() && z <= solveItemB.maxZ() )
					{
						// TODO: this might be unnecessary now
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
							final TileSpec tileSpec = solveItemA.idToTileSpec().get(tileId);

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

						final Tile< ? > tileA = solveItemDataToTile.get( solveItemA );
						final Tile< ? > tileB = solveItemDataToTile.get( solveItemB );

						tileA.connect( tileB, matchesAtoB );

						tileConfigBlocks.addTile( tileA );
						tileConfigBlocks.addTile( tileB );

						wasAssigned = true;
					}
				}

				// was this zPlane of solveItemA assigned with anything in this run?
				if ( !wasAssigned )
				{
					// if not, the reverse pair might have been assigned before (e.g. 0 and 69, now checking 69 that overlaps only with 0 and 1).
					boolean previouslyAssigned = false;

					if ( zToSolveItemPairs.containsKey( z ) )
					{
						for ( final Pair< ? extends Pair< ? extends SolveItemData< ?, ?, ? >, ? extends SolveItemData< ?, ?, ? > >, HashSet< String > > entry : zToSolveItemPairs.get( z ) )
						{
							if ( entry.getA().getA().equals( solveItemA ) || entry.getA().getB().equals( solveItemA ) )
							{
								previouslyAssigned = true;
								break;
							}
						}
					}

					if ( !previouslyAssigned )
					{
						// there is no overlap with any other solveItem (should be beginning or end of the entire stack)
						final HashSet< String > tileIds = solveItemA.zToTileId().get( z );

						// if there are none, we continue with the next
						if ( tileIds.size() == 0 )
							continue;
	
						gs.zToTileIdGlobal.putIfAbsent( z, new HashSet<>() );
						zToSolveItemPairs.putIfAbsent( z, new ArrayList<>() );
	
						// remember which solveItems defined which tileIds of this z section
						
						final SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > solveItemB =
								solveItemA.createCorrespondingDummySolveItem( id, z );
						zToSolveItemPairs.get( z ).add( new ValuePair<>( new ValuePair<>( solveItemA, solveItemB ), tileIds ) );
						solveItemDataToTile.putIfAbsent( solveItemB, new Tile( solveItemB.globalSolveModelInstance() ) );
	
						++id;

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
		}

		LOG.info( "launching Pre-Align, tileConfigBlocks has {} tiles and {} fixed tiles",
				  tileConfigBlocks.getTiles().size(), tileConfigBlocks.getFixedTiles().size() );

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

		final HashMap< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > >, AffineModel2D > blockToAffine2d = new HashMap<>();
	
		for ( final SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > solveItem : solveItemDataToTile.keySet() )
		{
			blockToAffine2d.put( solveItem, SolveTools.createAffine( solveItemDataToTile.get( solveItem ).getModel() ) );

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

					final AffineModel2D globalModelA = blockToAffine2d.get( solveItemA );
					modelA.preConcatenate( globalModelA );

					final AffineModel2D globalModelB = blockToAffine2d.get( solveItemB );
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
		}

		return gs;
	}

	protected void computeGlobalErrors(
			final GlobalSolve gs,
			final List< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > allSolveItems,
			final Model<?> crossLayerModel,
			final Model<?> montageLayerModel )
	{
		LOG.info( "Computing global errors ... " );

		// pTileId >> qTileId, Matches
		final HashMap< String, HashMap< String, Matches > > allMatches = new HashMap<>();

		// combine all matches from all solveitems
		for ( final SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > sid : allSolveItems )
		{
			/*
			for ( final Pair< Pair< String, String>, Matches > matchPair : sid.matches )
			{
				final String pTileId = matchPair.getA().getA();
				final String qTileId = matchPair.getA().getB();
				final Matches matches = matchPair.getB();

				allMatches.putIfAbsent( pTileId, new HashMap<String, Matches>() );
				final HashMap<String, Matches> qTileIdToMatches = allMatches.get( pTileId );

				if ( !qTileIdToMatches.containsKey( qTileId ) )
					qTileIdToMatches.put( qTileId, matches );
			}
			*/
		}

		if ( allMatches.size() == 0 )
		{
			LOG.info( "Matches were not saved, using a combination of local errors." );
			return;
		}
		else
		{
			LOG.info( "Clearing local block errors." );
			gs.idToErrorMapGlobal.clear();
		}

		// for local fits
		//final Model<?> crossLayerModel = new InterpolatedAffineModel2D<>( new AffineModel2D(), new RigidModel2D(), 0.25 );
		//final Model<?> montageLayerModel = this.stitchingModel.copy();

		for ( final String pTileId : allMatches.keySet() )
		{
			final HashMap<String, Matches> qTileIdToMatches = allMatches.get( pTileId );

			for ( final String qTileId : qTileIdToMatches.keySet() )
			{
				final Matches matches = qTileIdToMatches.get( qTileId );

				final TileSpec pTileSpec = gs.idToTileSpecGlobal.get(pTileId);
				final TileSpec qTileSpec = gs.idToTileSpecGlobal.get(qTileId);

				final double vDiff = SolveTools.computeAlignmentError(
						crossLayerModel, montageLayerModel, pTileSpec, qTileSpec, gs.idToFinalModelGlobal.get( pTileId ), gs.idToFinalModelGlobal.get( qTileId ), matches );

				gs.idToErrorMapGlobal.putIfAbsent( pTileId, new ArrayList<>() );
				gs.idToErrorMapGlobal.putIfAbsent( qTileId, new ArrayList<>() );

				gs.idToErrorMapGlobal.get( pTileId ).add( new SerializableValuePair<>( qTileId, vDiff ) );
				gs.idToErrorMapGlobal.get( qTileId ).add( new SerializableValuePair<>( pTileId, vDiff ) );
			}
		}

		LOG.info( "Done computing global errors." );
	}

	private static final Logger LOG = LoggerFactory.getLogger(DistributedSolve.class);
}

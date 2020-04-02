package org.janelia.render.client.solver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.janelia.alignment.match.CanvasMatchResult;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.Matches;
import org.janelia.alignment.spec.TileSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ij.ImageJ;
import ij.ImagePlus;
import mpicbg.models.AbstractAffineModel2D;
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
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;

public class DistributedSolveWorker< B extends Model< B > & Affine2D< B > >
{
	// attempts to stitch each section first (if the tiles are connected) and
	// then treat them as one big, "grouped" tile in the global optimization
	// the advantage is that potential deformations do not propagate into the individual
	// sections, but can be solved easily later using non-rigid alignment.
	final public static boolean stitchFirst = true;

	final public static double maxAllowedError = 10;
	final public static int numIterations = 500;
	final public static int maxPlateauWidth = 50;

	final protected static int visualizeZSection = 10000;

	final Parameters parameters;
	final RunParameters runParams;
	final SolveItem< B > inputSolveItem;
	final ArrayList< SolveItem< B > > solveItems;

	public DistributedSolveWorker( final Parameters parameters, final SolveItem< B > solveItem )
	{
		this.parameters = parameters;
		this.inputSolveItem = solveItem;
		this.runParams = solveItem.runParams();

		this.solveItems = new ArrayList<>();
	}

	public SolveItem< B > getInputSolveItems() { return inputSolveItem; }
	public ArrayList< SolveItem< B > > getSolveItems() { return solveItems; }

	protected void run() throws IOException, ExecutionException, InterruptedException, NoninvertibleModelException
	{
		assembleMatchData();
		split(); // splits

		for ( final SolveItem< B > solveItem : solveItems )
			solve( solveItem, parameters );
	}

	protected void assembleMatchData() throws IOException
	{
		LOG.info( "Loading transforms and matches from " + runParams.minZ + " to layer " + runParams.maxZ );

		// we store tile pairs and pointmatches here first, as we need to do stitching per section first if possible (if connected)
		final ArrayList< Pair< Pair< Tile< InterpolatedAffineModel2D<AffineModel2D, B> >, Tile< InterpolatedAffineModel2D<AffineModel2D, B> > >, List< PointMatch > > > pairs = new ArrayList<>();

		// maps from the z section to an entry in the above pairs list
		final HashMap< Integer, List< Integer > > zToPairs = new HashMap<>();

		for ( final Pair< String, Double > pGroupPair : runParams.pGroupList )
		{
			if ( pGroupPair.getB().doubleValue() < inputSolveItem.minZ() || pGroupPair.getB().doubleValue() > inputSolveItem.maxZ() )
				continue;

			final String pGroupId = pGroupPair.getA();

			LOG.info("run: connecting tiles with pGroupId {}", pGroupId);

			final List<CanvasMatches> matches = runParams.matchDataClient.getMatchesWithPGroupId(pGroupId, false);

			for (final CanvasMatches match : matches)
			{
				final String pId = match.getpId();
				final TileSpec pTileSpec = SolveTools.getTileSpec(parameters, runParams, pGroupId, pId);

				final String qGroupId = match.getqGroupId();
				final String qId = match.getqId();
				final TileSpec qTileSpec = SolveTools.getTileSpec(parameters, runParams, qGroupId, qId);

				if ((pTileSpec == null) || (qTileSpec == null))
				{
					LOG.info("run: ignoring pair ({}, {}) because one or both tiles are missing from stack {}", pId, qId, parameters.stack);
					continue;
				}

				// if any of the matches is outside the range we ignore them
				if ( pTileSpec.getZ() < inputSolveItem.minZ() || pTileSpec.getZ() > inputSolveItem.maxZ() || qTileSpec.getZ() < inputSolveItem.minZ() || qTileSpec.getZ() > inputSolveItem.maxZ() )
				{
					LOG.info("run: ignoring pair ({}, {}) because it is out of range {}", pId, qId, parameters.stack);
					continue;
				}

				/*
				// TODO: REMOVE Artificial split of the data
				if ( pTileSpec.getZ().doubleValue() == qTileSpec.getZ().doubleValue() )
				{
					if ( pTileSpec.getZ().doubleValue() >= 10049 && pTileSpec.getZ().doubleValue() <= 10149 )
					{
						if ( ( pId.contains( "_0-0-1." ) && qId.contains( "_0-0-2." ) ) || ( qId.contains( "_0-0-1." ) && pId.contains( "_0-0-2." ) ) )
						{
							LOG.info("run: ignoring pair ({}, {}) to artificially split the data", pId, qId );
							continue;
						}
					}
				}
				*/

				final Tile<InterpolatedAffineModel2D<AffineModel2D, B>> p, q;

				if ( !inputSolveItem.idToTileMap().containsKey( pId ) )
				{
					final Pair< Tile<InterpolatedAffineModel2D<AffineModel2D, B>>, AffineModel2D > pairP = SolveTools.buildTileFromSpec(parameters, pTileSpec);
					p = pairP.getA();
					inputSolveItem.idToTileMap().put( pId, p );
					inputSolveItem.idToPreviousModel().put( pId, pairP.getB() );
					inputSolveItem.idToTileSpec().put( pId, pTileSpec );

					inputSolveItem.tileToIdMap().put( p, pId );
				}
				else
				{
					p = inputSolveItem.idToTileMap().get( pId );
				}

				if ( !inputSolveItem.idToTileMap().containsKey( qId ) )
				{
					final Pair< Tile<InterpolatedAffineModel2D<AffineModel2D, B>>, AffineModel2D > pairQ = SolveTools.buildTileFromSpec(parameters, qTileSpec);
					q = pairQ.getA();
					inputSolveItem.idToTileMap().put( qId, q );
					inputSolveItem.idToPreviousModel().put( qId, pairQ.getB() );
					inputSolveItem.idToTileSpec().put( qId, qTileSpec );	

					inputSolveItem.tileToIdMap().put( q, qId );
				}
				else
				{
					q = inputSolveItem.idToTileMap().get( qId );
				}

				// remember the entries, need to perform section-based stitching before running global optimization
				if ( stitchFirst )
					pairs.add( new ValuePair<>( new ValuePair<>( p, q ), CanvasMatchResult.convertMatchesToPointMatchList(match.getMatches()) ) );
				else
					p.connect(q, CanvasMatchResult.convertMatchesToPointMatchList(match.getMatches()));

				final int pZ = (int)Math.round( pTileSpec.getZ() );
				final int qZ = (int)Math.round( qTileSpec.getZ() );

				inputSolveItem.zToTileId().putIfAbsent( pZ, new HashSet<>() );
				inputSolveItem.zToTileId().putIfAbsent( qZ, new HashSet<>() );

				inputSolveItem.zToTileId().get( pZ ).add( pId );
				inputSolveItem.zToTileId().get( qZ ).add( qId );

				// if the pair is from the same layer we remember the current index in the pairs list
				if ( stitchFirst && pZ == qZ )
				{
					zToPairs.putIfAbsent( pZ, new ArrayList<>() );
					zToPairs.get( pZ ).add( pairs.size() - 1 );
				}
			}
		}

		if ( stitchFirst )
		{
			stitchSections(
					inputSolveItem,
					pairs,
					zToPairs,
					new InterpolatedAffineModel2D<>( new RigidModel2D(), new TranslationModel2D(), 0.25 ) );

			// next, group the stitched tiles together
			for ( final Pair< Pair< Tile< InterpolatedAffineModel2D<AffineModel2D, B> >, Tile< InterpolatedAffineModel2D<AffineModel2D, B> > >, List< PointMatch > > pair : pairs )
			{
				final Tile< ? > p = inputSolveItem.tileToGroupedTile().get( pair.getA().getA() );
				final Tile< ? > q = inputSolveItem.tileToGroupedTile().get( pair.getA().getB() );

				final String pTileId = inputSolveItem.tileToIdMap().get( pair.getA().getA() );
				final String qTileId = inputSolveItem.tileToIdMap().get( pair.getA().getB() );

				final AffineModel2D pModel = inputSolveItem.idToStitchingModel().get( pTileId );
				final AffineModel2D qModel = inputSolveItem.idToStitchingModel().get( qTileId );

				p.connect(q, createRelativePointMatches( pair.getB(), pModel, qModel ) );
			}
		}
		//else
		//	we are done
	}

	protected < M extends Model< M > & Affine2D< M > > void stitchSections(
			final SolveItem< B > solveItem,
			final ArrayList< Pair< Pair< Tile< InterpolatedAffineModel2D<AffineModel2D, B> >, Tile< InterpolatedAffineModel2D<AffineModel2D, B> > >, List< PointMatch > > > pairs,
			final HashMap< Integer, List< Integer > > zToPairs,
			final M model )
	{
		// combine tiles per layer that are be stitched first
		final ArrayList< Integer > zList = new ArrayList<>( zToPairs.keySet() );
		Collections.sort( zList );

		for ( final int z : zList )
		{
			LOG.info( "" );
			LOG.info( "stitching z=" + z );

			final HashMap< String, Tile< M > > idTotile = new HashMap<>();
			final HashMap< Tile< M >, String > tileToId = new HashMap<>();

			// all connections within this z section
			for ( final int index : zToPairs.get( z ) )
			{
				final Pair< Pair< Tile< InterpolatedAffineModel2D<AffineModel2D, B> >, Tile< InterpolatedAffineModel2D<AffineModel2D, B> > >, List< PointMatch > > pair = pairs.get( index );
				
				final String pId = solveItem.tileToIdMap().get( pair.getA().getA() );
				final String qId = solveItem.tileToIdMap().get( pair.getA().getB() );

				//LOG.info( "pId=" + pId  + " (" + idTotile.containsKey( pId ) + ") " + " qId=" + qId + " (" + idTotile.containsKey( qId ) + ") " + idTotile.keySet().size() );

				final Tile< M > p, q;

				if ( !idTotile.containsKey( pId ) )
				{
					//p = new Tile<>( model.copy() );
					// since we do preAlign later this seems redundant. However, it makes sure the tiles are more or less at the right global coordinates
					p = SolveTools.buildTile( solveItem.idToPreviousModel().get( pId ), model.copy(), 100, 100, 3 );
					idTotile.put( pId, p );
					tileToId.put( p, pId );
				}
				else
				{
					p = idTotile.get( pId );
				}

				if ( !idTotile.containsKey( qId ) )
				{
					//q = new Tile<>( model.copy() );
					q = SolveTools.buildTile( solveItem.idToPreviousModel().get( qId ), model.copy(), 100, 100, 3 );
					idTotile.put( qId, q );
					tileToId.put( q, qId );
				}
				else
				{
					q = idTotile.get( qId );
				}

				// TODO: do we really need to duplicate the PointMatches?
				p.connect( q, duplicate( pair.getB() ) );
			}

			// add all missing TileIds as unconnected Tiles
			for ( final String tileId : solveItem.zToTileId().get( z ) )
				if ( !idTotile.containsKey( tileId ) )
				{
					LOG.info( "unconnected tileId " + tileId );

					final Tile< M > tile = new Tile< M >( model.copy() );
					idTotile.put( tileId, tile );
					tileToId.put( tile, tileId );
				}

			// Now identify connected graphs within all tiles
			final ArrayList< Set< Tile< ? > > > sets = Tile.identifyConnectedGraphs( idTotile.values() );

			LOG.info( "stitching z=" + z + " #sets=" + sets.size() );

			// solve each set (if size > 1)
			int setCount = 0;
			for ( final Set< Tile< ? > > set : sets )
			{
				LOG.info( "Set=" + setCount++ );

				// the grouped tile for this set
				final Tile<InterpolatedAffineModel2D<AffineModel2D, B>> groupedTile = new Tile<>(
						new InterpolatedAffineModel2D<>(
							new AffineModel2D(),
							parameters.regularizerModelType.getInstance(),
							parameters.startLambda)); // note: lambda gets reset during optimization loops

				if ( set.size() > 1 )
				{
					final TileConfiguration tileConfig = new TileConfiguration();
					tileConfig.addTiles( set );

					// we always prealign (not sure how far off the current alignment in renderer is)
					// a simple preAlign suffices for Translation and Rigid as it doesn't matter which Tile is fixed during alignment
					try
					{
						tileConfig.preAlign();
					}
					catch ( NotEnoughDataPointsException | IllDefinedDataPointsException e )
					{
						LOG.info( "Could not solve stitiching for z=" + z + ", cause: " + e );
						e.printStackTrace();
					}

					// test if the graph has cycles, if yes we would need to do a solve
					if ( !( ( TranslationModel2D.class.isInstance( model ) || RigidModel2D.class.isInstance( model ) ) && !new Graph( new ArrayList<>( set ) ).isCyclic() ) )
					{
						LOG.info( "Full solve required for stitching z=" + z  );

						try
						{
							TileUtil.optimizeConcurrently(
								new ErrorStatistic( maxPlateauWidth + 1 ),
								maxAllowedError,
								numIterations,
								maxPlateauWidth,
								1.0,
								tileConfig,
								tileConfig.getTiles(),
								tileConfig.getFixedTiles(),
								parameters.numberOfThreads);

							LOG.info( "Solve z=" + z + " avg=" + tileConfig.getError() + ", min=" + tileConfig.getMinError() + ", max=" + tileConfig.getMaxError() );
						}
						catch ( Exception e )
						{
							LOG.info( "Could not solve stitiching for z=" + z + ", cause: " + e );
							e.printStackTrace();
						}
					}

					// save Tile transformations accordingly
					for ( final Tile< ? > t : set )
					{
						final String tileId = tileToId.get( t );
						final AffineModel2D affine = createAffine( ((M)t.getModel()) );

						solveItem.idToStitchingModel().put( tileId, affine );

						// assign the original tile (we made a new one for stitching with a different model) to its grouped tile
						solveItem.tileToGroupedTile().put( solveItem.idToTileMap().get( tileId ), groupedTile );
						
						solveItem.groupedTileToTiles().putIfAbsent( groupedTile, new ArrayList<>() );
						solveItem.groupedTileToTiles().get( groupedTile ).add( solveItem.idToTileMap().get( tileId ) );

						LOG.info( "TileId " + tileId + " Model=" + affine );
					}

					// Hack: show a section after alignment
					if ( visualizeZSection == z )
					{
						try
						{
							new ImageJ();
							ImagePlus imp1 = SolveTools.render( solveItem.idToStitchingModel(), solveItem.idToTileSpec(), 0.15 );
							imp1.setTitle( "z=" + z );
						}
						catch ( NoninvertibleModelException e )
						{
							e.printStackTrace();
						}
					}

					//System.exit( 0 );
				}
				else
				{
					final String tileId = tileToId.get( set.iterator().next() );
					solveItem.idToStitchingModel().put( tileId, solveItem.idToPreviousModel().get( tileId ).copy() );

					// assign the original tile (we made a new one for stitching with a different model) to its grouped tile
					solveItem.tileToGroupedTile().put( solveItem.idToTileMap().get( tileId ), groupedTile );

					solveItem.groupedTileToTiles().putIfAbsent( groupedTile, new ArrayList<>() );
					solveItem.groupedTileToTiles().get( groupedTile ).add( solveItem.idToTileMap().get( tileId ) );

					LOG.info( "Single TileId " + tileId );
				}
			}
		}
	}

	protected static < M extends Model< M > & Affine2D< M > > AffineModel2D createAffine( final M model )
	{
		final AffineModel2D m = new AffineModel2D();
		m.set( model.createAffine() );

		return m;
	}

	protected static List< PointMatch > duplicate( List< PointMatch > pms )
	{
		final List< PointMatch > copy = new ArrayList<>();

		for ( final PointMatch pm : pms )
			copy.add( new PointMatch( pm.getP1().clone(), pm.getP2().clone(), pm.getWeight() ) );

		return copy;
	}

	public static List< PointMatch > createRelativePointMatches(
			final List< PointMatch > absolutePMs,
			final Model< ? > pModel,
			final Model< ? > qModel )
	{
		final List< PointMatch > relativePMs = new ArrayList<>( absolutePMs.size() );

		if ( absolutePMs.size() == 0 )
			return relativePMs;

		final int n = absolutePMs.get( 0 ).getP1().getL().length;

		for ( final PointMatch absPM : absolutePMs )
		{
			final double[] pLocal = new double[ n ];
			final double[] qLocal = new double[ n ];

			for (int d = 0; d < n; ++d )
			{
				pLocal[ d ] = absPM.getP1().getL()[ d ];
				qLocal[ d ] = absPM.getP2().getL()[ d ];
			}

			if ( pModel != null )
				pModel.applyInPlace( pLocal );

			if ( qModel != null )
				qModel.applyInPlace( qLocal );

			relativePMs.add( new PointMatch( new Point( pLocal ), new Point( qLocal ), absPM.getWeight() ) );
		}

		return relativePMs;
	}

	protected void split()
	{
		// the connectivity is defined by either idToTileMap().values() or tileToGroupedTile().values()
		final ArrayList< Set< Tile< ? > > > graphs;

		if ( stitchFirst )
			graphs = Tile.identifyConnectedGraphs( inputSolveItem.tileToGroupedTile().values() );
		else
			graphs = Tile.identifyConnectedGraphs( inputSolveItem.idToTileMap().values() );

		LOG.info( "Graph of SolveItem " + inputSolveItem.getId() + " consists of " + graphs.size() + " subgraphs." );

		if ( graphs.size() == 1 )
		{
			solveItems.add( inputSolveItem );

			LOG.info( "Graph 0 has " + graphs.get( 0 ).size() + " tiles." );
		}
		else
		{
			int graphCount = 0;

			for ( final Set< Tile< ? > > subgraph : graphs )
			{
				LOG.info( "new graph " + graphCount++ + "has " + subgraph.size() + " tiles." );

				int newMin = inputSolveItem.maxZ();
				int newMax = inputSolveItem.minZ();

				// first figure out new minZ and maxZ
				for ( final Tile< ? > t : subgraph )
				{
					final TileSpec tileSpec = inputSolveItem.idToTileSpec().get( inputSolveItem.tileToIdMap().get( t ) );

					newMin = Math.min( newMin, (int)Math.round( tileSpec.getZ().doubleValue() ) );
					newMax = Math.max( newMax, (int)Math.round( tileSpec.getZ().doubleValue() ) );
				}

				LOG.info( newMin + " > " + newMax );

				final SolveItem< B > solveItem = new SolveItem<>( newMin, newMax, runParams );

				LOG.info( "old graph id=" + inputSolveItem.getId() + ", new graph id=" + solveItem.getId() );

				// update all the maps
				for ( final Tile< ? > potentiallyGroupedTile : subgraph )
				{
					final ArrayList< Tile< ? > > tiles = new ArrayList<>();

					if ( stitchFirst )
						tiles.addAll( inputSolveItem.groupedTileToTiles().get( potentiallyGroupedTile ) );
					else
						tiles.add( potentiallyGroupedTile );
					
					for ( final Tile< ? > t : tiles )
					{
						final String tileId = inputSolveItem.tileToIdMap().get( t );
		
						solveItem.idToTileMap().put( tileId, (Tile)t );
						solveItem.tileToIdMap().put( t, tileId );
						solveItem.idToPreviousModel().put( tileId, inputSolveItem.idToPreviousModel().get( tileId ) );
						solveItem.idToTileSpec().put( tileId, inputSolveItem.idToTileSpec().get( tileId ) );
						solveItem.idToNewModel().put( tileId, inputSolveItem.idToNewModel().get( tileId ) );
		
						if ( DistributedSolveWorker.stitchFirst )
						{
							solveItem.idToStitchingModel().put( tileId, inputSolveItem.idToStitchingModel().get( tileId ) );
	
							final Tile< ? > groupedTile = inputSolveItem.tileToGroupedTile().get( t );
	
							solveItem.tileToGroupedTile().put( t, groupedTile );
							solveItem.groupedTileToTiles().putIfAbsent( groupedTile, inputSolveItem.groupedTileToTiles().get( groupedTile ) );
						}
					}
				}

				// used for global solve outside
				for ( int z = solveItem.minZ(); z <= solveItem.maxZ(); ++z )
				{
					final HashSet< String > allTilesPerZ = inputSolveItem.zToTileId().get( z );

					if ( allTilesPerZ == null )
						continue;

					final HashSet< String > myTilesPerZ = new HashSet<>();

					for ( final String tileId : allTilesPerZ )
					{
						if ( solveItem.idToTileMap().containsKey( tileId ) )
							myTilesPerZ.add( tileId );
					}
					
					if ( myTilesPerZ.size() == 0 )
					{
						LOG.info( "ERROR: z=" + z + " of new graph has 0 tileIds, the must not happen, this is a bug." );
						System.exit( 0 );
					}

					solveItem.zToTileId().put( z, myTilesPerZ );
				}

				solveItems.add( solveItem );
				// cannot update overlapping items here due to multithreading and the fact that the other solveitems are also being split up
			}
		}
		//System.exit( 0 );
	}

	protected static < B extends Model< B > & Affine2D< B > > void solve(
			final SolveItem< B > solveItem,
			final Parameters parameters
			) throws InterruptedException, ExecutionException
	{
		final TileConfiguration tileConfig = new TileConfiguration();

		if ( stitchFirst )
		{
			tileConfig.addTiles(solveItem.tileToGroupedTile().values());
			LOG.info("run: optimizing {} tiles for solveItem {}", solveItem.groupedTileToTiles().keySet().size(), solveItem.getId() );
		}
		else
		{
			tileConfig.addTiles(solveItem.idToTileMap().values());
			LOG.info("run: optimizing {} tiles for solveItem {}", solveItem.idToTileMap().size(), solveItem.getId() );
		}

		final List<Double> lambdaValues;

		if (parameters.optimizerLambdas == null)
			lambdaValues = Stream.of(1.0, 0.5, 0.1, 0.01)
					.filter(lambda -> lambda <= parameters.startLambda)
					.collect(Collectors.toList());
		else
			lambdaValues = parameters.optimizerLambdas.stream()
					.sorted(Comparator.reverseOrder())
					.collect(Collectors.toList());

		LOG.info( "lambda's used:" );

		for ( final double lambda : lambdaValues )
			LOG.info( "l=" + lambda );

		for (final double lambda : lambdaValues)
		{
			for (final Tile tile : solveItem.idToTileMap().values())
				((InterpolatedAffineModel2D) tile.getModel()).setLambda(lambda);

			int numIterations = parameters.maxIterations;
			if ( lambda == 1.0 || lambda == 0.5 )
				numIterations = 100;
			else if ( lambda == 0.1 )
				numIterations = 40;
			else if ( lambda == 0.01 )
				numIterations = 20;

			// tileConfig.optimize(parameters.maxAllowedError, parameters.maxIterations, parameters.maxPlateauWidth);
		
			LOG.info( "l=" + lambda + ", numIterations=" + numIterations );

			final ErrorStatistic observer = new ErrorStatistic(parameters.maxPlateauWidth + 1 );
			final float damp = 1.0f;
			TileUtil.optimizeConcurrently(
					observer,
					parameters.maxAllowedError,
					numIterations,
					parameters.maxPlateauWidth,
					damp,
					tileConfig,
					tileConfig.getTiles(),
					tileConfig.getFixedTiles(),
					parameters.numberOfThreads);
		}

		//
		// create lookup for the new models
		//
		solveItem.idToNewModel().clear();

		if ( stitchFirst )
		{
			// TODO:

			final ArrayList< String > tileIds = new ArrayList<>();
			final HashMap< String, AffineModel2D > tileIdToGroupModel = new HashMap<>();

			for ( final Tile< ? > tile : solveItem.tileToGroupedTile().keySet() )
			{
				final String tileId = solveItem.tileToIdMap().get( tile );

				tileIds.add( tileId );
				tileIdToGroupModel.put( tileId, ((InterpolatedAffineModel2D)solveItem.tileToGroupedTile().get( tile ).getModel()).createAffineModel2D() );
			}

			Collections.sort( tileIds );

			for (final String tileId : tileIds )
			{
				final AffineModel2D affine = solveItem.idToStitchingModel().get( tileId ).copy();

				affine.preConcatenate( tileIdToGroupModel.get( tileId ) );

				/*
				// TODO: REMOVE
				if ( inputSolveItem.getId() == 2 )
				{
				final TranslationModel2D t = new TranslationModel2D();
				t.set( 1000, 0 );
				affine.preConcatenate( t );
				}
				*/
	
				solveItem.idToNewModel().put( tileId, affine );
				LOG.info("tile {} model from grouped tile is {}", tileId, affine);
			}

		}
		else
		{
			final ArrayList< String > tileIds = new ArrayList<>( solveItem.idToTileMap().keySet() );
			Collections.sort( tileIds );
	
			for (final String tileId : tileIds )
			{
				final Tile<InterpolatedAffineModel2D<AffineModel2D, B>> tile = solveItem.idToTileMap().get(tileId);
				final AffineModel2D affine = tile.getModel().createAffineModel2D();
	
				/*
				// TODO: REMOVE
				if ( inputSolveItem.getId() == 2 )
				{
				final TranslationModel2D t = new TranslationModel2D();
				t.set( 1000, 0 );
				affine.preConcatenate( t );
				}
				*/
	
				solveItem.idToNewModel().put( tileId, affine );
				LOG.info("tile {} model is {}", tileId, affine);
			}
		}
	}

	private static final Logger LOG = LoggerFactory.getLogger(DistributedSolveWorker.class);
}

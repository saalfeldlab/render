package org.janelia.render.client.solver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.janelia.alignment.match.CanvasMatchResult;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.solver.matchfilter.MatchFilter;
import org.janelia.render.client.solver.matchfilter.NoMatchFilter;
import org.janelia.render.client.solver.matchfilter.RandomMaxAmountFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ij.ImageJ;
import ij.ImagePlus;
import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.IdentityModel;
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
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;

public class DistributedSolveWorker< G extends Model< G > & Affine2D< G >, B extends Model< B > & Affine2D< B >, S extends Model< S > & Affine2D< S > >
{
	// attempts to stitch each section first (if the tiles are connected) and
	// then treat them as one big, "grouped" tile in the global optimization
	// the advantage is that potential deformations do not propagate into the individual
	// sections, but can be solved easily later using non-rigid alignment.

	final protected static int visualizeZSection = 0;//10000;
	final private static int zRadiusRestarts = 10;
	final private static int stabilizationRadius = 25;

	final RenderDataClient renderDataClient;
	final RenderDataClient matchDataClient;
	final String stack;

	final List< Pair< String, Double > > pGroupList;
	final Map<String, ArrayList<Double>> sectionIdToZMap;

	// we store tile pairs and pointmatches here first, as we need to do stitching per section first if possible (if connected)
	// filled in assembleMatchData()
	final ArrayList< Pair< Pair< Tile< ? >, Tile< ? > >, List< PointMatch > > > pairs;

	// maps from the z section to an entry in the above pairs list
	// filled in assembleMatchData()
	final HashMap< Integer, List< Integer > > zToPairs;

	final int numThreads;

	final double maxAllowedErrorStitching;
	final int maxIterationsStitching, maxPlateauWidthStitching;

	final List<Double> blockOptimizerLambdasRigid, blockOptimizerLambdasTranslation;
	final List<Integer> blockOptimizerIterations, blockMaxPlateauWidth;
	final double blockMaxAllowedError;

	final SolveItem< G, B, S > inputSolveItem;
	List< SolveItem< G, B, S > > solveItems;

	// for assigning new id's when splitting solveItemData
	final int startId;

	// to filter matches
	final MatchFilter matchFilter;

	// for error computation (local)
	final ArrayList< CanvasMatches > canvasMatches = new ArrayList<>();

	public DistributedSolveWorker(
			final SolveItemData< G, B, S > solveItemData,
			final int startId,
			final List< Pair< String, Double > > pGroupList,
			final Map<String, ArrayList<Double>> sectionIdToZMap,
			final String baseDataUrl,
			final String owner,
			final String project,
			final String matchOwner,
			final String matchCollection,
			final String stack,
			final int maxNumMatches,
			final double maxAllowedErrorStitching,
			final int maxIterationsStitching,
			final int maxPlateauWidthStitching,
			final List<Double> blockOptimizerLambdasRigid,
			final List<Double> blockOptimizerLambdasTranslation,
			final List<Integer> blockOptimizerIterations,
			final List<Integer> blockMaxPlateauWidth,
			final double blockMaxAllowedError,
			final int numThreads )
	{
		this.renderDataClient = new RenderDataClient( baseDataUrl, owner, project );
		this.matchDataClient = new RenderDataClient( baseDataUrl, matchOwner, matchCollection );
		this.stack = stack;
		this.inputSolveItem = new SolveItem<>( solveItemData );
		this.startId = startId;
		this.pGroupList = pGroupList;
		this.sectionIdToZMap = sectionIdToZMap;

		this.blockOptimizerLambdasRigid = blockOptimizerLambdasRigid;
		this.blockOptimizerLambdasTranslation = blockOptimizerLambdasTranslation;
		this.blockOptimizerIterations = blockOptimizerIterations;
		this.blockMaxPlateauWidth = blockMaxPlateauWidth;

		this.maxAllowedErrorStitching = maxAllowedErrorStitching;
		this.maxIterationsStitching = maxIterationsStitching;
		this.maxPlateauWidthStitching = maxPlateauWidthStitching;
		this.blockMaxAllowedError = blockMaxAllowedError;

		this.numThreads = numThreads;

		if ( maxNumMatches <= 0 )
			this.matchFilter = new NoMatchFilter();
		else
			this.matchFilter = new RandomMaxAmountFilter( maxNumMatches );

		// used locally
		this.pairs = new ArrayList<>();
		this.zToPairs = new HashMap<>();
	}

	public List< SolveItemData< G, B, S > > getSolveItemDataList()
	{
		return solveItems.stream().map( SolveItem::getSolveItemData ).collect( Collectors.toList() );
	}

	protected void run() throws IOException, ExecutionException, InterruptedException, NoninvertibleModelException
	{
		assembleMatchData( pairs, zToPairs );
		stitchSectionsAndCreateGroupedTiles( inputSolveItem, pairs, zToPairs, numThreads );
		connectGroupedTiles( pairs, inputSolveItem );
		this.solveItems = splitSolveItem( inputSolveItem, startId );

		for ( final SolveItem< G, B, S > solveItem : solveItems )
		{
			if ( !assignRegularizationModel( solveItem ) )
				throw new RuntimeException( "Couldn't regularize. Please check." );
			solve( solveItem, zRadiusRestarts, numThreads );
		}

		for ( final SolveItem< G, B, S > solveItem : solveItems )
			computeErrors( solveItem, canvasMatches );
	}

	protected void assembleMatchData(
			final ArrayList< Pair< Pair< Tile< ? >, Tile< ? > >, List< PointMatch > > > pairs,
			final HashMap< Integer, List< Integer > > zToPairs ) throws IOException
	{
		final Map<Double, ResolvedTileSpecCollection> zToTileSpecsMap = new HashMap<>();

		LOG.info( "block " + inputSolveItem.getId() + ": Loading transforms and matches from " + inputSolveItem.minZ() + " to layer " + inputSolveItem.maxZ() );

		for ( final Pair< String, Double > pGroupPair : pGroupList )
		{
			if ( pGroupPair.getB().doubleValue() < inputSolveItem.minZ() || pGroupPair.getB().doubleValue() > inputSolveItem.maxZ() )
				continue;

			final String pGroupId = pGroupPair.getA();

			LOG.info("block " + inputSolveItem.getId() + ": run: connecting tiles with pGroupId {}", pGroupId);

			final List<CanvasMatches> matches = matchDataClient.getMatchesWithPGroupId(pGroupId, false);

			for (final CanvasMatches match : matches)
			{
				final String pId = match.getpId();
				final TileSpec pTileSpec = SolveTools.getTileSpec(sectionIdToZMap, zToTileSpecsMap, renderDataClient, stack, pGroupId, pId);

				final String qGroupId = match.getqGroupId();
				final String qId = match.getqId();
				final TileSpec qTileSpec = SolveTools.getTileSpec(sectionIdToZMap, zToTileSpecsMap, renderDataClient, stack, qGroupId, qId);

				if ((pTileSpec == null) || (qTileSpec == null))
				{
					LOG.info("block " + inputSolveItem.getId() + ": run: ignoring pair ({}, {}) because one or both tiles are missing from stack {}", pId, qId, stack);
					continue;
				}

				// if any of the matches is outside the range we ignore them
				if ( pTileSpec.getZ() < inputSolveItem.minZ() || pTileSpec.getZ() > inputSolveItem.maxZ() || qTileSpec.getZ() < inputSolveItem.minZ() || qTileSpec.getZ() > inputSolveItem.maxZ() )
				{
					LOG.info("block " + inputSolveItem.getId() + ": run: ignoring pair ({}, {}) because it is out of range {}", pId, qId, stack);
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

				final Tile< B > p, q;

				if ( !inputSolveItem.idToTileMap().containsKey( pId ) )
				{
					final Pair< Tile< B >, AffineModel2D > pairP = SolveTools.buildTileFromSpec( inputSolveItem.blockSolveModelInstance(), SolveItem.samplesPerDimension, pTileSpec);
					p = pairP.getA();
					inputSolveItem.idToTileMap().put( pId, p );
					inputSolveItem.idToPreviousModel().put( pId, pairP.getB() );

					final MinimalTileSpec pTileSpecMin = new MinimalTileSpec( pTileSpec );
					inputSolveItem.idToTileSpec().put( pId, pTileSpecMin );

					inputSolveItem.tileToIdMap().put( p, pId );

					if ( pTileSpecMin.isRestart() )
						inputSolveItem.restarts().add( (int)Math.round( pTileSpecMin.getZ() ) );
				}
				else
				{
					p = inputSolveItem.idToTileMap().get( pId );
				}

				if ( !inputSolveItem.idToTileMap().containsKey( qId ) )
				{
					final Pair< Tile< B >, AffineModel2D > pairQ = SolveTools.buildTileFromSpec( inputSolveItem.blockSolveModelInstance(), SolveItem.samplesPerDimension, qTileSpec);
					q = pairQ.getA();
					inputSolveItem.idToTileMap().put( qId, q );
					inputSolveItem.idToPreviousModel().put( qId, pairQ.getB() );

					final MinimalTileSpec qTileSpecMin = new MinimalTileSpec( qTileSpec );
					inputSolveItem.idToTileSpec().put( qId, qTileSpecMin );

					inputSolveItem.tileToIdMap().put( q, qId );

					if ( qTileSpecMin.isRestart() )
						inputSolveItem.restarts().add( (int)Math.round( qTileSpecMin.getZ() ) );
				}
				else
				{
					q = inputSolveItem.idToTileMap().get( qId );
				}

				// remember the entries, need to perform section-based stitching before running global optimization
				pairs.add( new ValuePair<>( new ValuePair<>( p, q ), matchFilter.filter( match.getMatches(), pTileSpec, qTileSpec ) ) );//CanvasMatchResult.convertMatchesToPointMatchList(match.getMatches()) ) );

				final int pZ = (int)Math.round( pTileSpec.getZ() );
				final int qZ = (int)Math.round( qTileSpec.getZ() );

				inputSolveItem.zToTileId().putIfAbsent( pZ, new HashSet<>() );
				inputSolveItem.zToTileId().putIfAbsent( qZ, new HashSet<>() );

				inputSolveItem.zToTileId().get( pZ ).add( pId );
				inputSolveItem.zToTileId().get( qZ ).add( qId );

				// if the pair is from the same layer we remember the current index in the pairs list for stitching
				if ( pZ == qZ )
				{
					zToPairs.putIfAbsent( pZ, new ArrayList<>() );
					zToPairs.get( pZ ).add( pairs.size() - 1 );
				}

				// for error computation
				this.canvasMatches.add( match );
			}
		}
	}
	/**
	 * The goal is to map the grouped tile to the averaged metadata coordinate transform
	 * (alternative: top left corner?)
	 * 
	 * @param solveItem - the input solve item
	 */
	protected boolean assignRegularizationModel( final SolveItem<G, B, S> solveItem )
	{
		LOG.info( "Assigning regularization models." );

		final HashMap< Integer, List<Tile<B>> > zToGroupedTileList = new HashMap<>();

		// new HashSet because all tiles link to their common group tile, which is therefore present more than once
		for ( final Tile< B > groupedTile : new HashSet<>( solveItem.tileToGroupedTile().values() ) )
		{
			final int z =
					(int)Math.round(
						solveItem.idToTileSpec().get(
							solveItem.tileToIdMap().get( 
									solveItem.groupedTileToTiles().get( groupedTile ).get( 0 ) ) ).getZ() );

			zToGroupedTileList.putIfAbsent(z, new ArrayList<>());
			zToGroupedTileList.get( z ).add( groupedTile );
		}
		
		final ArrayList< Integer > allZ = new ArrayList<>( zToGroupedTileList.keySet() );
		Collections.sort( allZ );

		if ( ConstantAffineModel2D.class.isInstance( ((InterpolatedAffineModel2D)zToGroupedTileList.get( allZ.get( 0 ) ).get( 0 ).getModel()).getB() ) )
		{
			//
			// it is based on ConstantAffineModels, meaning we extract metadata and use that as regularizer
			//
			for ( final int z : allZ )
			{
				final List<Tile<B>> groupedTiles = zToGroupedTileList.get( z );
	
				LOG.info( "z=" + z + " contains " + groupedTiles.size() + " grouped tiles (ConstantAffineModel2D)" );
	
				// find out where the Tile sits in average (given the n tiles it is grouped from)
				for ( final Tile< B > groupedTile : groupedTiles )
				{
					final List< Tile<B> > imageTiles = solveItem.groupedTileToTiles().get( groupedTile );
	
					if ( groupedTiles.size() > 1 )
						LOG.info( "z=" + z + " grouped tile [" + groupedTile + "] contains " + imageTiles.size() + " image tiles." );
	
					// regularization
					
					// ST (Stitching Transform)
					// PT (Previous Transform - from Render) -- that is what we want to regularize against
					
					// we should maybe decide for one of tiles, ideally the first that is present
					// what happens if only the second or third one is?
					
	//				TileId 19-07-31_120407_0-0-1.24501.0 Model=     [3,3](AffineTransform[[0.99999999990711, 1.180403435E-5, 8232.974854394946], [-1.180403435E-5, 0.99999999990711, 400.03365816280945]]) 1.7976931348623157E308
	//				TileId 19-07-31_120407_0-0-1.24501.0 prev Model=[3,3](AffineTransform[[1.0, 0.0, 8233.0], [0.0, 1.0, 400.0]]) 1.7976931348623157E308
	//				TileId 19-07-31_120407_0-0-2.24501.0 Model=     [3,3](AffineTransform[[0.999999260486421, 0.001053218791066, 16099.665150771456], [-0.001053218791066, 0.999999260486421, 401.52729791016964]]) 1.7976931348623157E308
	//				TileId 19-07-31_120407_0-0-2.24501.0 prev Model=[3,3](AffineTransform[[1.0, 0.0, 16066.0], [0.0, 1.0, 400.0]]) 1.7976931348623157E308
	//				TileId 19-07-31_120407_0-0-0.24501.0 Model=     [3,3](AffineTransform[[0.999999730969417, -6.35252550002E-4, 369.469292002579], [6.35252550002E-4, 0.999999730969417, 403.522028590144]]) 1.7976931348623157E308
	//				TileId 19-07-31_120407_0-0-0.24501.0 prev Model=[3,3](AffineTransform[[1.0, 0.0, 400.0], [0.0, 1.0, 400.0]]) 1.7976931348623157E308
	
					// create pointmatches from the edges of each image in the grouped tile to the respective edges in the metadata
					final List< PointMatch > matches = new ArrayList<>();
	
					for ( final Tile<B> imageTile : imageTiles )
					{
						final String tileId = solveItem.tileToIdMap().get( imageTile );
						final MinimalTileSpec tileSpec = solveItem.idToTileSpec().get( tileId );
						
						//if ( !tileId.contains("_0-0-0") )
						//	continue;
	
						final AffineModel2D stitchingTransform = solveItem.idToStitchingModel().get( tileId );
						final AffineModel2D metaDataTransform = getMetaDataTransformation( solveItem, tileId );
	
						//LOG.info( "z=" + z + " stitching model: " + stitchingTransform );
						//LOG.info( "z=" + z + " metaData model : " + metaDataTransform );
	
						final double sampleWidth = (tileSpec.getWidth() - 1.0) / (SolveItem.samplesPerDimension - 1.0);
						final double sampleHeight = (tileSpec.getHeight() - 1.0) / (SolveItem.samplesPerDimension - 1.0);
	
						// ALTERNATIVELY: ONLY SELECT ONE OF THE TILES
						for (int y = 0; y < SolveItem.samplesPerDimension; ++y)
						{
							final double sampleY = y * sampleHeight;
							for (int x = 0; x < SolveItem.samplesPerDimension; ++x)
							{
								final double[] p = new double[] { x * sampleWidth, sampleY };
								final double[] q = new double[] { x * sampleWidth, sampleY };
	
								stitchingTransform.applyInPlace( p );
								metaDataTransform.applyInPlace( q );
	
								matches.add(new PointMatch( new Point(p), new Point(q) ));
							}
						}					
					}
	
					//final RigidModel2D regularizationModel = new RigidModel2D();
					//final TranslationModel2D regularizationModel = new TranslationModel2D();
					//final S regularizationModel = solveItem.stitchingSolveModelInstance();
					
					final ConstantAffineModel2D cModel = (ConstantAffineModel2D)((InterpolatedAffineModel2D) groupedTile.getModel()).getB();
					final Model< ? > regularizationModel = cModel.getModel();
					
					try
					{
						regularizationModel.fit( matches );
											
						double sumError = 0;
						
						for ( final PointMatch pm : matches )
						{
							pm.getP1().apply( regularizationModel );
							
							final double distance = Point.distance(pm.getP1(), pm.getP2() );
							sumError += distance;
							
							//LOG.info( "P1: " + Util.printCoordinates( pm.getP1().getW() ) + ", P2: " + Util.printCoordinates( pm.getP2().getW() ) + ", d=" + distance );
						}
						LOG.info( "Error=" + (sumError / matches.size()) );
					}
					catch ( Exception e)
					{
						e.printStackTrace();
					}
				}
			}
			return true;
		}
		else
		{
			//
			// it is based on StabilizingAffineModel2Ds, meaning each image wants to sit where its corresponding one in the above layer sits
			//
			for ( int i = 0; i < allZ.size(); ++i )
			{
				final int z = allZ.get( i );

				// first get all tiles from adjacent layers and the associated grouped tile
				final ArrayList< Pair< Pair< Integer, String>, Tile<B> > > neighboringTiles = new ArrayList<>();

				int from = i, to = i;

				for ( int d = 1; d <= stabilizationRadius && i + d < allZ.size(); ++d )
				{
					if ( solveItem.restarts().contains( allZ.get( i + d ) ) )
						break;
					else
						neighboringTiles.addAll( SolveTools.layerDetails( allZ, zToGroupedTileList, solveItem, i + d ) );

					to = i + d;
				}

				// if this z section is a restart we only go down from here
				if ( !solveItem.restarts().contains( z ) )
				{
					for ( int d = 1; d <= stabilizationRadius && i - d >= 0; ++d )
					{
						// always connect up, even if it is a restart, then break afterwards
						neighboringTiles.addAll( SolveTools.layerDetails( allZ, zToGroupedTileList, solveItem, i - d ) );

						from = i - d;

						if ( solveItem.restarts().contains( allZ.get( i - d ) ) )
							break;
					}
				}

				final List<Tile<B>> groupedTiles = zToGroupedTileList.get( z );

				if ( solveItem.restarts().contains( z ) )
					LOG.info( "z=" + z + " is a RESTART" );

				LOG.info( "z=" + z + " contains " + groupedTiles.size() + " grouped tiles (StabilizingAffineModel2D), connected from " + allZ.get( from ) + " to " + allZ.get( to ) );

				// now go over all tiles of the current z
				for ( final Tile< B > groupedTile : groupedTiles )
				{
					final List< Tile<B> > imageTiles = solveItem.groupedTileToTiles().get( groupedTile );

					if ( groupedTiles.size() > 1 )
						LOG.info( "z=" + z + " grouped tile [" + groupedTile + "] contains " + imageTiles.size() + " image tiles." );
					
					// create pointmatches from the edges of each image in the grouped tile to the respective edges in the metadata
					final List< Pair< List< PointMatch >, Tile< B > > > matchesList = new ArrayList<>();

					for ( final Tile<B> imageTile : imageTiles )
					{
						final String tileId = solveItem.tileToIdMap().get( imageTile );
						final MinimalTileSpec tileSpec = solveItem.idToTileSpec().get( tileId );

						final int tileCol = tileSpec.getImageCol();

//						if ( tileCol != 0 )
//							continue;
		
						final ArrayList< Pair< Pair< Integer, String>, Tile<B> > > neighbors = new ArrayList<>();
						
						for ( final Pair< Pair< Integer, String>, Tile<B> > neighboringTile : neighboringTiles )
							if ( neighboringTile.getA().getA() == tileCol )
								neighbors.add( neighboringTile );

						if ( neighbors.size() == 0 )
						{
							// this can happen when number of tiles per layer changes for example
							LOG.info( "could not find corresponding tile for: " + tileId );
							continue;
						}

						for ( final Pair< Pair< Integer, String>, Tile<B> >  neighbor : neighbors )
						{
							final AffineModel2D stitchingTransform = solveItem.idToStitchingModel().get( tileId );
							final AffineModel2D stitchingTransformPrev = solveItem.idToStitchingModel().get( neighbor.getA().getB() );
	
							final List< PointMatch > matches = SolveTools.createFakeMatches(
									tileSpec.getWidth(),
									tileSpec.getHeight(),
									stitchingTransform, // p
									stitchingTransformPrev ); // q

							matchesList.add( new ValuePair<>( matches, neighbor.getB() ) );
						}
					}
					
					// in every iteration, update q with the current group tile transformation(s), the fit p to q for regularization
					final StabilizingAffineModel2D cModel = (StabilizingAffineModel2D)((InterpolatedAffineModel2D) groupedTile.getModel()).getB();
					
					cModel.setFitData( matchesList );
				}
			}
			return true;
		}
	}

	/**
	 * How to compute the metadata transformation, for now just using the previous transform
	 * 
	 * @param solveItem - which solveitem
	 * @param tileId - which TileId
	 * @return - AffineModel2D with the metadata transformation for this tile
	 */
	protected static AffineModel2D getMetaDataTransformation( final SolveItem<?, ?, ?> solveItem, final String tileId )
	{
		return solveItem.idToPreviousModel().get( tileId );
	}

	protected void connectGroupedTiles(
			final ArrayList< Pair< Pair< Tile< ? >, Tile< ? > >, List< PointMatch > > > pairs,
			final SolveItem<G, B, S> solveItem )
	{
		// next, group the stitched tiles together
		for ( final Pair< Pair< Tile< ? >, Tile< ? > >, List< PointMatch > > pair : pairs )
		{
			final Tile< ? > p = solveItem.tileToGroupedTile().get( pair.getA().getA() );
			final Tile< ? > q = solveItem.tileToGroupedTile().get( pair.getA().getB() );

			if ( p == q )
				continue;
			
			final String pTileId = solveItem.tileToIdMap().get( pair.getA().getA() );
			final String qTileId = solveItem.tileToIdMap().get( pair.getA().getB() );

			final AffineModel2D pModel = solveItem.idToStitchingModel().get( pTileId ); // ST for p
			final AffineModel2D qModel = solveItem.idToStitchingModel().get( qTileId ); // ST for q

			p.connect(q, SolveTools.createRelativePointMatches( pair.getB(), pModel, qModel ) );
		}
	}

	protected void stitchSectionsAndCreateGroupedTiles(
			final SolveItem< G,B,S > solveItem,
			final ArrayList< Pair< Pair< Tile< ? >, Tile< ? > >, List< PointMatch > > > pairs,
			final HashMap< Integer, List< Integer > > zToPairs,
			final int numThreads )
	{
		final S model = solveItem.stitchingSolveModelInstance();

		// combine tiles per layer that are be stitched first, but iterate over all z's 
		// (also those only consisting of single tiles, they are connected in z though)
		final ArrayList< Integer > zList = new ArrayList<>( solveItem.zToTileId().keySet() );
		Collections.sort( zList );

		for ( final int z : zList )
		{
			LOG.info( "block " + solveItem.getId() + ": stitching z=" + z );

			final HashMap< String, Tile< S > > idTotile = new HashMap<>();
			final HashMap< Tile< S >, String > tileToId = new HashMap<>();

			// all connections within this z section
			if ( zToPairs.containsKey( z ) )
			{
				for ( final int index : zToPairs.get( z ) )
				{
					final Pair< Pair< Tile< ? >, Tile< ? > >, List< PointMatch > > pair = pairs.get( index );
					
					final String pId = solveItem.tileToIdMap().get( pair.getA().getA() );
					final String qId = solveItem.tileToIdMap().get( pair.getA().getB() );
	
					//LOG.info( "pId=" + pId  + " (" + idTotile.containsKey( pId ) + ") " + " qId=" + qId + " (" + idTotile.containsKey( qId ) + ") " + idTotile.keySet().size() );
	
					final Tile< S > p, q;
	
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
					p.connect( q, SolveTools.duplicate( pair.getB() ) );
				}
			}

			// add all missing TileIds as unconnected Tiles
			for ( final String tileId : solveItem.zToTileId().get( z ) )
				if ( !idTotile.containsKey( tileId ) )
				{
					LOG.info( "block " + solveItem.getId() + ": unconnected tileId " + tileId );

					final Tile< S > tile = new Tile< S >( model.copy() );
					idTotile.put( tileId, tile );
					tileToId.put( tile, tileId );
				}

			// Now identify connected graphs within all tiles
			final ArrayList< Set< Tile< ? > > > sets = Tile.identifyConnectedGraphs( idTotile.values() );

			LOG.info( "block " + solveItem.getId() + ": stitching z=" + z + " #sets=" + sets.size() );

			// solve each set (if size > 1)
			int setCount = 0;
			for ( final Set< Tile< ? > > set : sets )
			{
				LOG.info( "block " + solveItem.getId() + ": Set=" + setCount++ );

				//
				// the grouped tile for this set of one layer
				//
				final Tile< B > groupedTile = new Tile<>( solveItem.blockSolveModelInstance() );

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
						LOG.info( "block " + solveItem.getId() + ": Could not solve prealign for z=" + z + ", cause: " + e );
						e.printStackTrace();
					}

					// test if the graph has cycles, if yes we would need to do a solve
					if ( !( ( TranslationModel2D.class.isInstance( model ) || RigidModel2D.class.isInstance( model ) ) && !new Graph( new ArrayList<>( set ) ).isCyclic() ) )
					{
						LOG.info( "block " + solveItem.getId() + ": Full solve required for stitching z=" + z  );

						try
						{
							TileUtil.optimizeConcurrently(
								new ErrorStatistic( maxPlateauWidthStitching + 1 ),
								maxAllowedErrorStitching,
								maxIterationsStitching,
								maxPlateauWidthStitching,
								1.0,
								tileConfig,
								tileConfig.getTiles(),
								tileConfig.getFixedTiles(),
								numThreads );

							LOG.info( "block " + solveItem.getId() + ": Solve z=" + z + " avg=" + tileConfig.getError() + ", min=" + tileConfig.getMinError() + ", max=" + tileConfig.getMaxError() );
						}
						catch ( Exception e )
						{
							LOG.info( "block " + solveItem.getId() + ": Could not solve stitiching for z=" + z + ", cause: " + e );
							e.printStackTrace();
						}
					}

					// save Tile transformations accordingly
					for ( final Tile< ? > t : set )
					{
						final String tileId = tileToId.get( t );
						final AffineModel2D affine = SolveTools.createAffine( ((Affine2D<?>)t.getModel()) );

						solveItem.idToStitchingModel().put( tileId, affine );

						// assign the original tile (we made a new one for stitching with a different model) to its grouped tile
						solveItem.tileToGroupedTile().put( solveItem.idToTileMap().get( tileId ), groupedTile );
						
						solveItem.groupedTileToTiles().putIfAbsent( groupedTile, new ArrayList<>() );
						solveItem.groupedTileToTiles().get( groupedTile ).add( solveItem.idToTileMap().get( tileId ) );

						LOG.info( "block " + solveItem.getId() + ": TileId " + tileId + " Model=     " + affine );
						LOG.info( "block " + solveItem.getId() + ": TileId " + tileId + " prev Model=" + solveItem.idToPreviousModel().get( tileId ) );
					}

					// Hack: show a section after alignment
					if ( visualizeZSection == z )
					{
						try
						{
							final HashMap< String, AffineModel2D > models = new HashMap<>();
							for ( final Tile< ? > t : set )
							{
								final String tileId = tileToId.get( t );
								models.put( tileId, solveItem.idToStitchingModel().get( tileId ) );
							}

							new ImageJ();
							ImagePlus imp1 = VisualizeTools.render( models, solveItem.idToTileSpec(), 0.15 );
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

					LOG.info( "block " + inputSolveItem.getId() + ": Single TileId " + tileId );
				}
			}
		}
	}

	protected List< SolveItem< G, B, S > > splitSolveItem( final SolveItem<G, B, S> inputSolveItem, final int startId )
	{
		// assigning new id's to the solve items (they collide for now with other workers, fix upon merging)
		int id = startId + 1;

		final ArrayList< SolveItem< G, B, S > > solveItems = new ArrayList<>();

		// new HashSet because all tiles link to their common group tile, which is therefore present more than once
		final ArrayList< Set< Tile< ? > > > graphs = Tile.identifyConnectedGraphs( new HashSet<>( inputSolveItem.tileToGroupedTile().values() ) );

		LOG.info( "block " + inputSolveItem.getId() + ": Graph of SolveItem " + inputSolveItem.getId() + " consists of " + graphs.size() + " subgraphs." );

		if ( graphs.size() == 1 )
		{
			solveItems.add( inputSolveItem );

			LOG.info( "block " + inputSolveItem.getId() + ": Graph 0 has " + graphs.get( 0 ).size() + " tiles." );
		}
		else
		{
			int graphCount = 0;

			for ( final Set< Tile< ? > > subgraph : graphs ) // TODO: type sets properly
			{
				LOG.info( "block " + inputSolveItem.getId() + ": new graph " + graphCount++ + " has " + subgraph.size() + " tiles." );

				int newMin = inputSolveItem.maxZ();
				int newMax = inputSolveItem.minZ();

				// first figure out new minZ and maxZ
				for ( final Tile< ? > groupedTile : subgraph )
				{
					for ( final Tile< ? > t : inputSolveItem.groupedTileToTiles().get( groupedTile ) )
					{
						final MinimalTileSpec tileSpec = inputSolveItem.idToTileSpec().get( inputSolveItem.tileToIdMap().get( t ) );
	
						newMin = Math.min( newMin, (int)Math.round( tileSpec.getZ() ) );
						newMax = Math.max( newMax, (int)Math.round( tileSpec.getZ() ) );
					}
				}

				final SolveItem< G,B,S > solveItem = new SolveItem<>(
						new SolveItemData< G, B, S >(
							id,
							inputSolveItem.globalSolveModelInstance(),
							inputSolveItem.blockSolveModelInstance(),
							inputSolveItem.stitchingSolveModelInstance(),
							newMin,
							newMax ) );

				++id;

				LOG.info( "block " + solveItem.getId() + ": old graph id=" + inputSolveItem.getId() + ", new graph id=" + solveItem.getId() );
				LOG.info( "block " + solveItem.getId() + ": min: " + newMin + " > max: " + newMax );

				// update all the maps
				for ( final Tile< ? > groupedTile : subgraph )
				{
					for ( final Tile< B > t : inputSolveItem.groupedTileToTiles().get( groupedTile ) )
					{
						final String tileId = inputSolveItem.tileToIdMap().get( t );
		
						solveItem.idToTileMap().put( tileId, t );
						solveItem.tileToIdMap().put( t, tileId );
						solveItem.idToPreviousModel().put( tileId, inputSolveItem.idToPreviousModel().get( tileId ) );
						solveItem.idToTileSpec().put( tileId, inputSolveItem.idToTileSpec().get( tileId ) );
						solveItem.idToNewModel().put( tileId, inputSolveItem.idToNewModel().get( tileId ) );

						solveItem.idToStitchingModel().put( tileId, inputSolveItem.idToStitchingModel().get( tileId ) );

						final Tile< B > groupedTileCast = inputSolveItem.tileToGroupedTile().get( t );

						solveItem.tileToGroupedTile().put( t, groupedTileCast );
						solveItem.groupedTileToTiles().putIfAbsent( groupedTileCast, inputSolveItem.groupedTileToTiles().get( groupedTile ) );
					}
				}

				// add the restart lookup
				for ( final int z : inputSolveItem.restarts() )
					if ( z >= newMin && z <= newMax )
						solveItem.restarts().add( z );

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
						LOG.info( "block " + solveItem.getId() + ": ERROR: z=" + z + " of new graph has 0 tileIds, the must not happen, this is a bug." );
						System.exit( 0 );
					}

					solveItem.zToTileId().put( z, myTilesPerZ );
				}

				solveItems.add( solveItem );
			}
		}
		return solveItems;
	}

	protected void solve(
			final SolveItem< G,B,S > solveItem,
			final int zRadiusRestarts,
			final int numThreads
			) throws InterruptedException, ExecutionException
	{
		final TileConfiguration tileConfig = new TileConfiguration();

		// new HashSet because all tiles link to their common group tile, which is therefore present more than once
		tileConfig.addTiles( new HashSet<>( solveItem.tileToGroupedTile().values() ) );

		LOG.info("block " + solveItem.getId() + ": run: optimizing {} tiles", solveItem.groupedTileToTiles().keySet().size() );

		final HashMap< Tile< ? >, Double > tileToDynamicLambda = SolveTools.computeMetaDataLambdas( tileConfig.getTiles(), solveItem, zRadiusRestarts );

		LOG.info( "block " + solveItem.getId() + ": prealigning with translation and dynamic lambda" );

		for (final Tile< ? > tile : tileConfig.getTiles() )
		{
			((InterpolatedAffineModel2D)((InterpolatedAffineModel2D)((InterpolatedAffineModel2D) tile.getModel()).getA()).getA()).setLambda( 1.0 ); // rigid vs affine
			((InterpolatedAffineModel2D)((InterpolatedAffineModel2D) tile.getModel()).getA()).setLambda( 0.0 ); // translation
			((InterpolatedAffineModel2D) tile.getModel()).setLambda( 0.0 ); // prealign without regularization
		}
		
		try
		{
			double[] errors = SolveTools.computeErrors( tileConfig.getTiles() );
			LOG.info( "errors: " + errors[ 0 ] + "/" + errors[ 1 ] + "/" + errors[ 2 ] );

			final Map< Tile< ? >, Integer > tileToZ = new HashMap<>();

			for ( final Tile< ? > tile : tileConfig.getTiles() )
				tileToZ.put( tile, (int)Math.round( solveItem.idToTileSpec().get( solveItem.tileToIdMap().get( solveItem.groupedTileToTiles().get( tile ).get( 0 ) ) ).getZ() ) );

			SolveTools.preAlignByLayerDistance( tileConfig, tileToZ );
			//tileConfig.preAlign();
			
			errors = SolveTools.computeErrors( tileConfig.getTiles() );
			LOG.info( "errors: " + errors[ 0 ] + "/" + errors[ 1 ] + "/" + errors[ 2 ] );
		}
		catch (NotEnoughDataPointsException | IllDefinedDataPointsException e)
		{
			LOG.info( "block " + solveItem.getId() + ": prealign failed: " + e );
			e.printStackTrace();
		}

		LOG.info( "block " + solveItem.getId() + ": lambda's used (rigid, translation):" );
	
		for ( int l = 0; l < blockOptimizerLambdasRigid.size(); ++l )
		{
			LOG.info( "block " + solveItem.getId() + ": l=" + blockOptimizerLambdasRigid.get( l ) + ", " + blockOptimizerLambdasTranslation.get( l ) );
		}

		for ( int s = 0; s < blockOptimizerLambdasRigid.size(); ++s )
		{
			final double lambdaRigid = blockOptimizerLambdasRigid.get( s );
			final double lambdaTranslation = blockOptimizerLambdasTranslation.get( s );

			for (final Tile< ? > tile : tileConfig.getTiles() )
			{
				((InterpolatedAffineModel2D)((InterpolatedAffineModel2D)((InterpolatedAffineModel2D) tile.getModel()).getA()).getA()).setLambda( lambdaRigid);
				((InterpolatedAffineModel2D)((InterpolatedAffineModel2D) tile.getModel()).getA()).setLambda( lambdaTranslation );
				((InterpolatedAffineModel2D) tile.getModel()).setLambda( tileToDynamicLambda.get( tile ) ); // dynamic
			}
			
			int numIterations = blockOptimizerIterations.get( s );

			final int maxPlateauWidth = blockMaxPlateauWidth.get( s );

			LOG.info( "block " + solveItem.getId() + ": l(rigid)=" + lambdaRigid + ", l(translation)=" + lambdaTranslation + ", numIterations=" + numIterations + ", maxPlateauWidth=" + maxPlateauWidth );

			final ErrorStatistic observer = new ErrorStatistic( maxPlateauWidth + 1 );
			final float damp = 1.0f;
			TileUtil.optimizeConcurrently(
					observer,
					blockMaxAllowedError,
					numIterations,
					maxPlateauWidth,
					damp,
					tileConfig,
					tileConfig.getTiles(),
					tileConfig.getFixedTiles(),
					numThreads );
		}

		double[] errors = SolveTools.computeErrors( tileConfig.getTiles() );
		LOG.info( "errors: " + errors[ 0 ] + "/" + errors[ 1 ] + "/" + errors[ 2 ] );

		//
		// create lookup for the new models
		//
		solveItem.idToNewModel().clear();

		final ArrayList< String > tileIds = new ArrayList<>();
		final HashMap< String, AffineModel2D > tileIdToGroupModel = new HashMap<>();

		for ( final Tile< ? > tile : solveItem.tileToGroupedTile().keySet() )
		{
			final String tileId = solveItem.tileToIdMap().get( tile );

			tileIds.add( tileId );
			tileIdToGroupModel.put( tileId, SolveTools.createAffine( (Affine2D<?>)solveItem.tileToGroupedTile().get( tile ).getModel() ) );
		}

		Collections.sort( tileIds );

		for (final String tileId : tileIds )
		{
			final AffineModel2D affine = solveItem.idToStitchingModel().get( tileId ).copy();

			affine.preConcatenate( tileIdToGroupModel.get( tileId ) );

			LOG.info("block " + solveItem.getId() + ": grouped model for tile {} is {}", tileId, tileIdToGroupModel.get( tileId ));

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
			LOG.info("block " + solveItem.getId() + ": tile {} model from grouped tile is {}", tileId, affine);
		}
	}

	protected void computeErrors( final SolveItem< G,B,S > solveItem, final ArrayList< CanvasMatches > canvasMatches )
	{
		LOG.info( "Computing errors for " + solveItem.idToTileSpec().keySet().size() + " tiles using " + canvasMatches.size() + " pairs of images ..." );

		// for local fits
		final InterpolatedAffineModel2D< AffineModel2D, RigidModel2D > crossLayerModel = new InterpolatedAffineModel2D<>( new AffineModel2D(), new RigidModel2D(), 0.25 );
		final S montageLayerModel = solveItem.stitchingSolveModelInstance();

		// for fitting local to global pair
		final RigidModel2D rigidModel = new RigidModel2D();

		for ( final CanvasMatches match : canvasMatches )
		{
			final String pTileId = match.getpId();
			final String qTileId = match.getqId();

			final MinimalTileSpec pTileSpec = solveItem.idToTileSpec().get( pTileId );
			final MinimalTileSpec qTileSpec = solveItem.idToTileSpec().get( qTileId );

			// it is from a different solveitem
			if ( pTileSpec == null || qTileSpec == null )
				continue;

			final List< PointMatch > global = SolveTools.createFakeMatches(
					pTileSpec.getWidth(),
					pTileSpec.getHeight(),
					solveItem.idToNewModel().get( pTileId ), // p
					solveItem.idToNewModel().get( qTileId ) ); // q

			// the actual matches, local solve
			final List< PointMatch > pms = CanvasMatchResult.convertMatchesToPointMatchList( match.getMatches() );

			final Model< ? > model;

			if ( pTileSpec.getZ() == qTileSpec.getZ() )
				model = montageLayerModel;
			else
				model = crossLayerModel;

			try
			{
				model.fit( pms );
			}
			catch ( Exception e )
			{
				e.printStackTrace();
			}

			final List< PointMatch > local = SolveTools.createFakeMatches(
					pTileSpec.getWidth(),
					pTileSpec.getHeight(),
					model, // p
					new IdentityModel() ); // q

			// match the local solve to the global solve rigidly, as the entire stack is often slightly rotated
			// but do not change the transformations relative to each other (in local, global)
			final ArrayList< PointMatch > relativeMatches = new ArrayList<>();

			for ( int i = 0; i < global.size(); ++i )
			{
				relativeMatches.add( new PointMatch( new Point( local.get( i ).getP1().getL().clone() ), new Point( global.get( i ).getP1().getL().clone() ) ) );
				relativeMatches.add( new PointMatch( new Point( local.get( i ).getP2().getL().clone() ), new Point( global.get( i ).getP2().getL().clone() ) ) );
			}

			try
			{
				rigidModel.fit( relativeMatches );
			}
			catch (Exception e){}

			double vDiff = 0;

			for ( int i = 0; i < global.size(); ++i )
			{
				final double dGx = global.get( i ).getP2().getL()[ 0 ] - global.get( i ).getP1().getL()[ 0 ];
				final double dGy = global.get( i ).getP2().getL()[ 1 ] - global.get( i ).getP1().getL()[ 1 ];

				final Point l1 = local.get( i ).getP1();
				final Point l2 = local.get( i ).getP2();

				l1.apply( rigidModel );
				l2.apply( rigidModel );

				final double dLx = l2.getW()[ 0 ] - l1.getW()[ 0 ];
				final double dLy = l2.getW()[ 1 ] - l1.getW()[ 1 ];

				vDiff += SolveTools.distance( dLx, dLy, dGx, dGy );
			}

			// Stitching error is almost zero (vdiff = 0.0271) if all points are used (NoMatchFilter).
			vDiff /= (double)global.size();

			solveItem.idToErrorMap().putIfAbsent( pTileId, new ArrayList<>() );
			solveItem.idToErrorMap().putIfAbsent( qTileId, new ArrayList<>() );

			solveItem.idToErrorMap().get( pTileId ).add( new SerializableValuePair<>( qTileId, vDiff ) );
			solveItem.idToErrorMap().get( qTileId ).add( new SerializableValuePair<>( pTileId, vDiff ) );
		}
	}

	private static final Logger LOG = LoggerFactory.getLogger(DistributedSolveWorker.class);
}

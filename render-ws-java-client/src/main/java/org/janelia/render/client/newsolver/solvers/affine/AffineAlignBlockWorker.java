package org.janelia.render.client.newsolver.solvers.affine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMAlignmentParameters;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMAlignmentParameters.PreAlign;
import org.janelia.render.client.newsolver.solvers.Worker;
import org.janelia.render.client.newsolver.solvers.WorkerTools;
import org.janelia.render.client.newsolver.solvers.WorkerTools.LayerDetails;
import org.janelia.render.client.solver.ConstantAffineModel2D;
import org.janelia.render.client.solver.Graph;
import org.janelia.render.client.solver.SerializableValuePair;
import org.janelia.render.client.solver.SolveTools;
import org.janelia.render.client.solver.StabilizingAffineModel2D;
import org.janelia.render.client.solver.matchfilter.MatchFilter;
import org.janelia.render.client.solver.matchfilter.NoMatchFilter;
import org.janelia.render.client.solver.matchfilter.RandomMaxAmountFilter;
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
import mpicbg.models.TranslationModel2D;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;

public class AffineAlignBlockWorker< M extends Model< M > & Affine2D< M >, S extends Model< S > & Affine2D< S >, F extends BlockFactory< F > > extends Worker< M, AffineModel2D, FIBSEMAlignmentParameters< M, S >, F >
{
	// attempts to stitch each section first (if the tiles are connected) and
	// then treat them as one big, "grouped" tile in the global optimization
	// the advantage is that potential deformations do not propagate into the individual
	// sections, but can be solved easily later using non-rigid alignment.

	final protected static int visualizeZSection = 0;//10000;
	//final private static int zRadiusRestarts = 10;
	final private static int stabilizationRadius = 25;

	final RenderDataClient matchDataClient;

	// we store tile pairs and pointmatches here first, as we need to do stitching per section first if possible (if connected)
	// filled in assembleMatchData()
	final ArrayList< Pair< Pair< Tile< ? >, Tile< ? > >, List< PointMatch > > > pairs;

	// maps from the z section to an entry in the above pairs list
	// filled in assembleMatchData()
	final HashMap< Integer, List< Integer > > zToPairs;

	// only for dynamic lambda stuff that we never used
	//final Set<Integer> excludeFromRegularization;

	//final List<Double> blockOptimizerLambdasRigid, blockOptimizerLambdasTranslation;
	//final List<Integer> blockOptimizerIterations, blockMaxPlateauWidth;
	//final double blockMaxAllowedError;

	final AffineBlockDataWrapper< M, S, F > inputSolveItem;
	private ArrayList< AffineBlockDataWrapper< M, S, F > > solveItems;
	private ArrayList< BlockData< M, AffineModel2D, FIBSEMAlignmentParameters< M, S >, F > > result;

	// to filter matches
	final MatchFilter matchFilter;

	// if stitching first should be done
	final boolean stitchFirst;

	// for error computation (local)
	ArrayList< CanvasMatches > canvasMatches;

	// created by SolveItemData.createWorker()
	public AffineAlignBlockWorker(
			final BlockData< M, AffineModel2D, FIBSEMAlignmentParameters< M, S >, F > blockData,
			final int startId,
			final int numThreads )
	{
		super( startId, blockData, numThreads );

		this.matchDataClient =
				new RenderDataClient(
						blockData.solveTypeParameters().baseDataUrl(),
						blockData.solveTypeParameters().matchOwner(),
						blockData.solveTypeParameters().matchCollection() );

		this.inputSolveItem = new AffineBlockDataWrapper<>( blockData );

		if ( blockData.solveTypeParameters().maxNumMatches() <= 0 )
			this.matchFilter = new NoMatchFilter();
		else
			this.matchFilter = new RandomMaxAmountFilter( blockData.solveTypeParameters().maxNumMatches() );

		// used locally
		this.stitchFirst = blockData.solveTypeParameters().minStitchingInliersSupplier() != null;
		this.pairs = new ArrayList<>();
		this.zToPairs = new HashMap<>();

		// NOTE: if you choose to stitch first, you need to pre-align, otherwise, it's OK to use the initial alignment for each tile
		if ( stitchFirst && inputSolveItem.blockData().solveTypeParameters().preAlign() == PreAlign.NONE )
		{
			LOG.error( "Since you choose to stitch first, you must pre-align with Translation or Rigid." );
			throw new RuntimeException( "Since you choose to stitch first, you must pre-align with Translation or Rigid." );
		}
	}

	@Override
	public ArrayList< BlockData< M, AffineModel2D, FIBSEMAlignmentParameters< M, S >, F > > getBlockDataList()
	{
		return result;
	}

	@Override
	public void run() throws IOException, ExecutionException, InterruptedException, NoninvertibleModelException
	{
		try
		{
			// TODO: trautmane
			this.canvasMatches = assembleMatchData( inputSolveItem, matchFilter, matchDataClient, renderDataClient, renderStack, pairs, zToPairs );
	
			// minStitchingInliersSupplier:
			// how many stitching inliers are needed to stitch first
			// reason: if tiles are rarely connected and it is stitched first, a useful
			// common alignment model after stitching cannot be found
			stitchSectionsAndCreateGroupedTiles( inputSolveItem, pairs, zToPairs, stitchFirst, numThreads );
	
			connectGroupedTiles( pairs, inputSolveItem );
	
			this.solveItems = splitSolveItem( inputSolveItem, startId );
	
			for ( final AffineBlockDataWrapper< M, S, F > solveItem : solveItems )
			{
				/*
				java.lang.NullPointerException: Cannot invoke "org.janelia.alignment.spec.TileSpec.getZ()" because the return value of "org.janelia.alignment.spec.ResolvedTileSpecCollection.getTileSpec(String)" is null
						at org.janelia.render.client.newsolver.solvers.affine.AffineAlignBlockWorker.assignRegularizationModel(AffineAlignBlockWorker.java:345)
						at org.janelia.render.client.newsolver.solvers.affine.AffineAlignBlockWorker.run(AffineAlignBlockWorker.java:164)
						at org.janelia.render.client.newsolver.DistributedAffineXYBlockSolver.lambda$1(DistributedAffineXYBlockSolver.java:108)
						at java.base/java.util.concurrent.FutureTask.run(FutureTask.java:264)
						at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)
						at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)
						at java.base/java.lang.Thread.run(Thread.java:833)
				*/
	
				if ( !assignRegularizationModel( solveItem, AffineBlockDataWrapper.samplesPerDimension, stabilizationRadius ) )
					throw new RuntimeException( "Couldn't regularize. Please check." );
	
				solve( solveItem, numThreads );
			}
	
			for ( final AffineBlockDataWrapper< M, S, F > solveItem : solveItems )
				computeSolveItemErrors( solveItem, canvasMatches );
	
			// clean up
			this.result = new ArrayList<>();
			for ( final AffineBlockDataWrapper< M, S, F > solveItem : solveItems )
			{
				result.add( solveItem.blockData() );
				solveItem.matches().clear();
				solveItem.tileToGroupedTile().clear();
				solveItem.groupedTileToTiles().clear();
				solveItem.idToTileMap().clear();
			}
			this.solveItems.clear();
			this.solveItems = null;
			System.gc();
		}
		catch ( Exception e )
		{
			LOG.info( "Exception: " +e );
			e.printStackTrace();
		}
	}

	protected ArrayList< CanvasMatches > assembleMatchData(
			final AffineBlockDataWrapper< M, S, F > inputSolveItem,
			final MatchFilter matchFilter,
			final RenderDataClient matchDataClient,
			final RenderDataClient renderDataClient,
			final String renderStack,
			final ArrayList< Pair< Pair< Tile< ? >, Tile< ? > >, List< PointMatch > > > pairs,
			final HashMap< Integer, List< Integer > > zToPairs ) throws IOException
	{
		final Map<String, ArrayList<Double>> sectionIdToZMap = inputSolveItem.blockData().sectionIdToZMap();
		final int minZ = inputSolveItem.blockData().minZ();
		final int maxZ = inputSolveItem.blockData().maxZ();
		final int maxZRangeMatches = inputSolveItem.blockData().solveTypeParameters().maxZRangeMatches();

		final ArrayList< CanvasMatches > canvasMatches = new ArrayList<>();
		final Map<Double, ResolvedTileSpecCollection> zToTileSpecsMap = new HashMap<>();

		LOG.info( "block " + inputSolveItem.blockData().getId() + ": Loading transforms and matches for " + inputSolveItem.blockData().rtsc().getTileCount() + " tiles, from " + minZ + " to layer " + maxZ );

		if ( maxZRangeMatches >= 0 )
			LOG.info( "block " + inputSolveItem.blockData().getId() + ": WARNING! max z range for matching is " + maxZRangeMatches );

		// sort sectionIds
		final ArrayList< String > sortedSectionIds = new ArrayList<>( sectionIdToZMap.keySet() );
		sortedSectionIds.sort((s1, s2) -> Double.compare(Double.parseDouble(s1), Double.parseDouble(s2)));

		for ( final String pGroupId : sortedSectionIds )
		{
			LOG.info("block {}: run: connecting tiles with pGroupId {}",
					 inputSolveItem.blockData().getId(), pGroupId);

			final List<CanvasMatches> serviceMatchList = matchDataClient.getMatchesWithPGroupId(pGroupId,
																								false);

			for (final CanvasMatches match : serviceMatchList)
			{
				final String pId = match.getpId();
				final TileSpec pTileSpec = SolveTools.getTileSpec(sectionIdToZMap, zToTileSpecsMap, renderDataClient, renderStack, pGroupId, pId);

				final String qGroupId = match.getqGroupId();
				final String qId = match.getqId();
				final TileSpec qTileSpec = SolveTools.getTileSpec(sectionIdToZMap, zToTileSpecsMap, renderDataClient, renderStack, qGroupId, qId);

				if ((pTileSpec == null) || (qTileSpec == null))
				{
					LOG.info("block " + inputSolveItem.blockData().getId() + ": run: ignoring pair ({}, {}) because one or both tiles are missing from stack {}", pId, qId, renderStack );
					continue;
				}

				// if any of the matches is outside the set of tiles of this Block we ignore them
				if ( !inputSolveItem.blockData().rtsc().getTileIds().contains( qTileSpec.getTileId() ) )
				{
					// commented because it is too many outputs
					//LOG.info("block " + inputSolveItem.blockData().getId() + ": run: ignoring pair ({}, {}) because it is out of range {}", pId, qId, renderStack);
					continue;
				}
	
				// if any of the matches is outside the range we ignore them
				if ( pTileSpec.getZ() < minZ || pTileSpec.getZ() > maxZ || qTileSpec.getZ() < minZ || qTileSpec.getZ() > maxZ )
				{
					LOG.info("block " + inputSolveItem.blockData().getId() + ": run: ignoring pair ({}, {}) because it is out of range in z - THIS CANNOT HAPPEN. STOPPING. {}", pId, qId, renderStack);
					System.exit( 0 );
				}

				// max range
				if ( maxZRangeMatches >= 0 && Math.abs( pTileSpec.getZ() - qTileSpec.getZ() ) > maxZRangeMatches )
					continue;

				final Tile<M> p = getOrBuildTile(pId, pTileSpec);
				final Tile<M> q = getOrBuildTile(qId, qTileSpec);

				// remember the entries, need to perform section-based stitching before running global optimization
				pairs.add( new ValuePair<>( new ValuePair<>( p, q ), matchFilter.filter( match.getMatches(), pTileSpec, qTileSpec ) ) );//CanvasMatchResult.convertMatchesToPointMatchList(match.getMatches()) ) );

				final int pZ = (int)Math.round( pTileSpec.getZ() );
				final int qZ = (int)Math.round( qTileSpec.getZ() );

				inputSolveItem.blockData().zToTileId().computeIfAbsent(pZ, k -> new HashSet<>()).add(pId);
				inputSolveItem.blockData().zToTileId().computeIfAbsent(qZ, k -> new HashSet<>()).add(qId);

				// if the pair is from the same layer we remember the current index in the pairs list for stitching
				if ( pZ == qZ )
				{
					zToPairs.computeIfAbsent(pZ, k -> new ArrayList<>()).add(pairs.size() - 1);
				}

				// for error computation
				canvasMatches.add( match );

				//LOG.info("block " + inputSolveItem.blockData().getId() + ": run: linking pair ({}, {}) ", pId, qId  );
			}
		}

		return canvasMatches;
	}

	protected Tile<M> getOrBuildTile( final String id, final TileSpec tileSpec )
	{
		final Tile<M> tile;
		if (!inputSolveItem.idToTileMap().containsKey(id))
		{
			final Pair<Tile<M>, AffineModel2D> pair =
					SolveTools.buildTileFromSpec( inputSolveItem.blockData().solveTypeParameters().blockSolveModel().copy(), AffineBlockDataWrapper.samplesPerDimension, tileSpec );
			tile = pair.getA();
			inputSolveItem.idToTileMap().put(id, tile);
			inputSolveItem.tileToIdMap().put(tile, id);

			inputSolveItem.idToPreviousModel().put(id, pair.getB());
			//inputSolveItem.blockData().idToTileSpec().put(id, minimalSpecWrapper); // this is now done ahead of time

			//if ( tileSpec.hasLabel( "restart" ) )
			//	inputSolveItem.restarts().add((int) Math.round(tileSpec.getZ()));
		}
		else
		{
			tile = inputSolveItem.idToTileMap().get(id);
		}

		return tile;
	}

	/**
	 * The goal is to map the grouped tile to the averaged metadata coordinate transform
	 * (alternative: top left corner?)
	 * 
	 * @param solveItem - the solve item
	 * @param samplesPerDimension - for creating fake matches using ConstantAffineModel2D or StabilizingAffineModel2D
	 * @param stabilizationRadius - the radius in z that is used for stabilization using StabilizingAffineModel2D
	 */
	protected boolean assignRegularizationModel(
			final AffineBlockDataWrapper< M, S, F > solveItem,
			final int samplesPerDimension,
			final int stabilizationRadius )
	{
		LOG.info( "Assigning regularization models." );

		final HashMap< Integer, List<Tile<M>> > zToGroupedTileList = new HashMap<>();

		// new HashSet because all tiles link to their common group tile, which is therefore present more than once
		for ( final Tile< M > groupedTile : new HashSet<>( solveItem.tileToGroupedTile().values() ) )
		{
			// TODO: 001_000003_078_20220405_180741.1241.0 is only in Block id=1, id=9, not id=0
			// but it is part of solveItem.tileToIdMap().get( solveItem.groupedTileToTiles().get( groupedTile ).get( 0 ) )
			// so somehow it gets in here when assembling everything (must be XY-specific I guess)
			try
			{
			final int z =
					(int)Math.round(
						solveItem.blockData().rtsc().getTileSpec(
							solveItem.tileToIdMap().get( 
									solveItem.groupedTileToTiles().get( groupedTile ).get( 0 ) ) ).getZ() );

			zToGroupedTileList.putIfAbsent(z, new ArrayList<>());
			zToGroupedTileList.get( z ).add( groupedTile );
			}
			catch ( Exception e )
			{
				e.printStackTrace();
				System.out.println( solveItem.tileToIdMap().get( solveItem.groupedTileToTiles().get( groupedTile ).get( 0 ) ) );
				System.exit( 0 );
			}
		}
		
		final ArrayList< Integer > allZ = new ArrayList<>( zToGroupedTileList.keySet() );
		Collections.sort( allZ );

		final Model<?> model = ((InterpolatedAffineModel2D<?,?>) zToGroupedTileList.get(allZ.get(0)).get(0).getModel()).getB();

		if (model instanceof ConstantAffineModel2D)
		{
			//
			// it is based on ConstantAffineModels, meaning we extract metadata and use that as regularizer
			//
			for ( final int z : allZ )
			{
				final List<Tile<M>> groupedTiles = zToGroupedTileList.get( z );

				LOG.info( "z=" + z + " contains " + groupedTiles.size() + " grouped tiles (ConstantAffineModel2D)" );

				// find out where the Tile sits in average (given the n tiles it is grouped from)
				for ( final Tile< M > groupedTile : groupedTiles )
				{
					final List< Tile< M> > imageTiles = solveItem.groupedTileToTiles().get( groupedTile );
	
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

					for ( final Tile<M> imageTile : imageTiles )
					{
						final String tileId = solveItem.tileToIdMap().get( imageTile );
						final TileSpec tileSpec = solveItem.blockData().rtsc().getTileSpec( tileId );
						
						//if ( !tileId.contains("_0-0-0") )
						//	continue;
	
						final AffineModel2D stitchingTransform = solveItem.idToStitchingModel().get( tileId );
						final AffineModel2D metaDataTransform = getMetaDataTransformation( solveItem, tileId );
	
						//LOG.info( "z=" + z + " stitching model: " + stitchingTransform );
						//LOG.info( "z=" + z + " metaData model : " + metaDataTransform );
	
						final double sampleWidth = (tileSpec.getWidth() - 1.0) / (samplesPerDimension - 1.0);
						final double sampleHeight = (tileSpec.getHeight() - 1.0) / (samplesPerDimension - 1.0);

						// ALTERNATIVELY: ONLY SELECT ONE OF THE TILES
						for (int y = 0; y < samplesPerDimension; ++y)
						{
							final double sampleY = y * sampleHeight;
							for (int x = 0; x < samplesPerDimension; ++x)
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
					} catch (final Exception e) {
						LOG.info("Caught exception: ", e);
					}
				}
			}
			return true;
		}
		else if (model instanceof StabilizingAffineModel2D)
		{
			//
			// it is based on StabilizingAffineModel2Ds, meaning each image wants to sit where its corresponding one in the above layer sits
			//
			for ( int i = 0; i < allZ.size(); ++i )
			{
				final int z = allZ.get( i );

				// first get all tiles from adjacent layers and the associated grouped tile
				final ArrayList< LayerDetails< M > > neighboringTiles = new ArrayList<>();

				int from = i, to = i;

				for ( int d = 1; d <= stabilizationRadius && i + d < allZ.size(); ++d )
				{
					//if ( solveItem.restarts().contains( allZ.get( i + d ) ) )
					//	break;
					//else
						neighboringTiles.addAll( WorkerTools.layerDetails( allZ, zToGroupedTileList, solveItem, i + d ) );

					to = i + d;
				}

				// if this z section is a restart we only go down from here
				// if ( !solveItem.restarts().contains( z ) )
				{
					for ( int d = 1; d <= stabilizationRadius && i - d >= 0; ++d )
					{
						// always connect up, even if it is a restart, then break afterwards
						neighboringTiles.addAll( WorkerTools.layerDetails( allZ, zToGroupedTileList, solveItem, i - d ) );

						from = i - d;

						//if ( solveItem.restarts().contains( allZ.get( i - d ) ) )
						//	break;
					}
				}

				final List< Tile< M > > groupedTiles = zToGroupedTileList.get( z );

				//if ( solveItem.restarts().contains( z ) )
				//	LOG.info( "z=" + z + " is a RESTART" );

				LOG.info( "z=" + z + " contains " + groupedTiles.size() + " grouped tiles (StabilizingAffineModel2D), connected from " + allZ.get( from ) + " to " + allZ.get( to ) );

				// now go over all tiles of the current z
				for ( final Tile< M > groupedTile : groupedTiles )
				{
					final List< Tile< M > > imageTiles = solveItem.groupedTileToTiles().get( groupedTile );

					if ( groupedTiles.size() > 1 )
						LOG.info( "z=" + z + " grouped tile [" + groupedTile + "] contains " + imageTiles.size() + " image tiles." );
					
					// create pointmatches from the edges of each image in the grouped tile to the respective edges in the metadata
					final List< Pair< List< PointMatch >, Tile< M > > > matchesList = new ArrayList<>();

					for ( final Tile< M > imageTile : imageTiles )
					{
						final String tileId = solveItem.tileToIdMap().get( imageTile );
						final TileSpec tileSpec = solveItem.blockData().rtsc().getTileSpec( tileId );

						final int tileCol = tileSpec.getLayout().getImageCol();// tileSpec.getImageCol();
						final int tileRow = tileSpec.getLayout().getImageRow();

//						if ( tileCol != 0 )
//							continue;
		
						final ArrayList< LayerDetails< M > > neighbors = new ArrayList<>();
						
						for ( final LayerDetails< M > neighboringTile : neighboringTiles )
							if ( neighboringTile.tileCol == tileCol && neighboringTile.tileRow == tileRow )
								neighbors.add( neighboringTile );

						if (neighbors.isEmpty())
						{
							// this can happen when number of tiles per layer changes for example
							LOG.info( "could not find corresponding tile for: " + tileId );
							continue;
						}

						for ( final LayerDetails< M > neighbor : neighbors )
						{
							final AffineModel2D stitchingTransform = solveItem.idToStitchingModel().get( tileId );
							final AffineModel2D stitchingTransformPrev = solveItem.idToStitchingModel().get( neighbor.tileId );
	
							final List< PointMatch > matches = SolveTools.createFakeMatches(
									tileSpec.getWidth(),
									tileSpec.getHeight(),
									stitchingTransform, // p
									stitchingTransformPrev, // q
									samplesPerDimension );

							matchesList.add( new ValuePair<>( matches, neighbor.prevGroupedTile ) );
						}
					}

					// in every iteration, update q with the current group tile transformation(s), the fit p to q for regularization
					final StabilizingAffineModel2D cModel = (StabilizingAffineModel2D)((InterpolatedAffineModel2D) groupedTile.getModel()).getB();

					cModel.setFitData( matchesList );
				}
			}
			return true;
		}
		else
		{
			LOG.info( "Not using ConstantAffineModel2D for regularization. Nothing to do in assignRegularizationModel()." );
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
	protected static AffineModel2D getMetaDataTransformation( final AffineBlockDataWrapper<?, ?, ?> solveItem, final String tileId )
	{
		return solveItem.idToPreviousModel().get( tileId );
	}

	protected void connectGroupedTiles(
			final ArrayList< Pair< Pair< Tile< ? >, Tile< ? > >, List< PointMatch > > > pairs,
			final AffineBlockDataWrapper< M, S, F > solveItem )
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
			final AffineBlockDataWrapper< M, S, F > solveItem,
			final ArrayList< Pair< Pair< Tile< ? >, Tile< ? > >, List< PointMatch > > > pairs,
			final HashMap< Integer, List< Integer > > zToPairs,
			final boolean stitchFirst,
			final int numThreads )
	{
		final int maxPlateauWidthStitching = solveItem.blockData().solveTypeParameters().maxPlateauWidthStitching();
		final double maxAllowedErrorStitching = solveItem.blockData().solveTypeParameters().maxAllowedErrorStitching();
		final int maxIterationsStitching = solveItem.blockData().solveTypeParameters().maxIterationsStitching();
		final Function< Integer, Integer > minStitchingInliersSupplier = solveItem.blockData().solveTypeParameters().minStitchingInliersSupplier();

		//final S model = solveItem.stitchingSolveModelInstance();

		// combine tiles per layer that are be stitched first, but iterate over all z's 
		// (also those only consisting of single tiles, they are connected in z though)
		final ArrayList< Integer > zList = new ArrayList<>( solveItem.blockData().zToTileId().keySet() );
		Collections.sort( zList );

		for ( final int z : zList )
		{
			LOG.info( "block " + solveItem.blockData().getId() + ": stitching z=" + z );

			final HashMap< String, Tile< S > > idTotile = new HashMap<>();
			final HashMap< Tile< S >, String > tileToId = new HashMap<>();

			if ( stitchFirst )
			{
				// all connections within this z section
				if ( zToPairs.containsKey( z ) )
				{
					for ( final int index : zToPairs.get( z ) )
					{
						final Pair< Pair< Tile< ? >, Tile< ? > >, List< PointMatch > > pair = pairs.get( index );

						// stitching first only works when the stitching is reliable
						if ( pair.getB().size() < minStitchingInliersSupplier.apply( z ) )
							continue;

						final String pId = solveItem.tileToIdMap().get( pair.getA().getA() );
						final String qId = solveItem.tileToIdMap().get( pair.getA().getB() );

						// hack for Z0720_07m_VNC Sec19
//						if ( pId.contains("_0-0-0") || qId.contains("_0-0-0") ) {
//							LOG.info("stitchSectionsAndCreateGroupedTiles: do not stitch first {} and second {} tiles", pId, qId);
//							continue;
//						}

						//LOG.info( "pId=" + pId  + " (" + idTotile.containsKey( pId ) + ") " + " qId=" + qId + " (" + idTotile.containsKey( qId ) + ") " + idTotile.keySet().size() );
		
						final Tile< S > p, q;
		
						if ( !idTotile.containsKey( pId ) )
						{
							//p = new Tile<>( model.copy() );
							// since we do preAlign later this seems redundant. However, it makes sure the tiles are more or less at the right global coordinates
							p = SolveTools.buildTile(
									solveItem.idToPreviousModel().get( pId ),
									solveItem.blockData().solveTypeParameters().stitchingSolveModelInstance( z ).copy(),
									100, 100, 3 );
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
							q = SolveTools.buildTile(
									solveItem.idToPreviousModel().get( qId ),
									solveItem.blockData().solveTypeParameters().stitchingSolveModelInstance( z ).copy(),
									100, 100, 3 );
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
			}

			// add all missing TileIds as unconnected Tiles
			for ( final String tileId : solveItem.blockData().zToTileId().get( z ) )
				if ( !idTotile.containsKey( tileId ) )
				{
					LOG.info( "block " + solveItem.blockData().getId() + ": unconnected tileId " + tileId );

					final Tile< S > tile = new Tile<>(solveItem.blockData().solveTypeParameters().stitchingSolveModelInstance(z).copy());
					idTotile.put( tileId, tile );
					tileToId.put( tile, tileId );
				}

			// Now identify connected graphs within all tiles
			final ArrayList< Set< Tile< ? > > > sets = safelyIdentifyConnectedGraphs( new ArrayList<>(idTotile.values()) );

			LOG.info( "block " + solveItem.blockData().getId() + ": stitching z=" + z + " #sets=" + sets.size() );

			// solve each set (if size > 1)
			int setCount = 0;
			for ( final Set< Tile< ? > > set : sets )
			{
				LOG.info( "block " + solveItem.blockData().getId() + ": Set=" + setCount++ );

				//
				// the grouped tile for this set of one layer
				//
				final Tile< M > groupedTile = new Tile<>( solveItem.blockData().solveTypeParameters().blockSolveModel().copy() );

				if ( set.size() > 1 )
				{
					final TileConfiguration tileConfig = new TileConfiguration();
					tileConfig.addTiles( set );

					// we always prealign (not sure how far off the current alignment in renderer is)
					// a simple preAlign suffices for Translation and Rigid as it doesn't matter which Tile is fixed during alignment
					try {
						tileConfig.preAlign();
					} catch (final NotEnoughDataPointsException | IllDefinedDataPointsException e) {
						LOG.info("block " + solveItem.blockData().getId() + ": Could not solve prealign for z=" + z + ", cause: ", e);
					}

					// test if the graph has cycles, if yes we would need to do a solve
					if ( !( (
							set.iterator().next().getModel() instanceof TranslationModel2D ||
							set.iterator().next().getModel() instanceof RigidModel2D) &&
							!new Graph( new ArrayList<>( set ) ).isCyclic() ) )
					{
						LOG.info( "block " + solveItem.blockData().getId() + ": Full solve required for stitching z=" + z  );

						try {
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

							LOG.info( "block " + solveItem.blockData().getId() + ": Solve z=" + z + " avg=" + tileConfig.getError() + ", min=" + tileConfig.getMinError() + ", max=" + tileConfig.getMaxError() );
						} catch (final Exception e) {
							LOG.info("block " + solveItem.blockData().getId() + ": Could not solve stitiching for z=" + z + ", cause: ", e);
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

						LOG.info( "block " + solveItem.blockData().getId() + ": TileId " + tileId + " Model=     " + affine );
						LOG.info( "block " + solveItem.blockData().getId() + ": TileId " + tileId + " prev Model=" + solveItem.idToPreviousModel().get( tileId ) );
					}

					// Hack: show a section after alignment
					if ( visualizeZSection == z )
					{
						try {
							final HashMap< String, AffineModel2D > models = new HashMap<>();
							for ( final Tile< ? > t : set )
							{
								final String tileId = tileToId.get( t );
								models.put( tileId, solveItem.idToStitchingModel().get( tileId ) );
							}

							new ImageJ();
							final ImagePlus imp1 = VisualizeTools.renderTS(models, solveItem.blockData().rtsc().getTileIdToSpecMap(), 0.15 );
							imp1.setTitle( "z=" + z );
						} catch (final NoninvertibleModelException e) {
							LOG.info("Could not show section: ", e);
						}
					}
				}
				else
				{
					final String tileId = tileToId.get( set.iterator().next() );
					solveItem.idToStitchingModel().put( tileId, solveItem.idToPreviousModel().get( tileId ).copy() );

					// assign the original tile (we made a new one for stitching with a different model) to its grouped tile
					solveItem.tileToGroupedTile().put( solveItem.idToTileMap().get( tileId ), groupedTile );

					solveItem.groupedTileToTiles().putIfAbsent( groupedTile, new ArrayList<>() );
					solveItem.groupedTileToTiles().get( groupedTile ).add( solveItem.idToTileMap().get( tileId ) );

					LOG.info( "block " + solveItem.blockData().getId() + ": Single TileId " + tileId );
				}
			}
		}
	}

	protected ArrayList< AffineBlockDataWrapper< M, S, F > > splitSolveItem( final AffineBlockDataWrapper< M, S, F > inputSolveItem, final int startId )
	{
		// assigning new id's to the solve items (they collide for now with other workers, fix upon merging)
		int id = startId + 1;

		final ArrayList< AffineBlockDataWrapper< M, S, F > > solveItems = new ArrayList<>();

		// new HashSet because all tiles link to their common group tile, which is therefore present more than once
		final ArrayList< Set< Tile< ? > > > graphs = safelyIdentifyConnectedGraphs( new HashSet<>( inputSolveItem.tileToGroupedTile().values() ) );

		LOG.info( "block " + inputSolveItem.blockData().getId() + ": Graph of SolveItem " + inputSolveItem.blockData().getId() + " consists of " + graphs.size() + " subgraphs." );

		if (graphs.isEmpty())
		{
			throw new RuntimeException( "Something went wrong. The inputsolve item has 0 subgraphs. stopping." );
		}
		else if ( graphs.size() == 1 )
		{
			solveItems.add( inputSolveItem );

			LOG.info( "block " + inputSolveItem.blockData().getId() + ": Graph 0 has " + graphs.get( 0 ).size() + " tiles." );
		}
		else
		{
			int graphCount = 0;

			for ( final Set< Tile< ? > > subgraph : graphs ) // TODO: type sets properly
			{
				LOG.info( "block " + inputSolveItem.blockData().getId() + ": new graph " + graphCount++ + " has " + subgraph.size() + " tiles." );


				// re-assemble allTileIds and idToTileSpec
				// update all the maps
				final ResolvedTileSpecCollection originalRTSC = inputSolveItem.blockData().rtsc();
				final List<TileSpec> groupedTileSpecList = subgraph.stream()
						.map(groupedTile -> inputSolveItem.groupedTileToTiles().get(groupedTile))
						.flatMap(Collection::stream)
						.map(tile -> inputSolveItem.tileToIdMap().get(tile))
						.map(originalRTSC::getTileSpec)
						.collect(Collectors.toList());

				final ResolvedTileSpecCollection newRTSC =
						new ResolvedTileSpecCollection(originalRTSC.getTransformSpecs(),
													   groupedTileSpecList);

				final AffineBlockDataWrapper< M, S, F > solveItem =
						new AffineBlockDataWrapper<>(
								new BlockData<>(
										inputSolveItem.blockData().blockFactory(), // no copy necessary
										inputSolveItem.blockData().solveTypeParameters(), // no copy necessary
										id,
										newRTSC ) );

				++id;

				LOG.info( "block " + solveItem.blockData().getId() + ": old graph id=" + inputSolveItem.blockData().getId() + ", new graph id=" + solveItem.blockData().getId() );
				LOG.info( "block " + solveItem.blockData().getId() + ": min: " + solveItem.blockData().minZ() + " > max: " + solveItem.blockData().maxZ() );

				// update all the maps
				for ( final Tile< ? > groupedTile : subgraph )
				{
					for ( final Tile< M > t : inputSolveItem.groupedTileToTiles().get( groupedTile ) )
					{
						final String tileId = inputSolveItem.tileToIdMap().get( t );
		
						solveItem.idToTileMap().put( tileId, t );
						solveItem.tileToIdMap().put( t, tileId );
						solveItem.idToPreviousModel().put( tileId, inputSolveItem.idToPreviousModel().get( tileId ) );
						//solveItem.idToTileSpec().put( tileId, inputSolveItem.blockData().idToTileSpec().get( tileId ) ); // now done initially
						solveItem.blockData().idToNewModel().put( tileId, inputSolveItem.blockData().idToNewModel().get( tileId ) );

						solveItem.idToStitchingModel().put( tileId, inputSolveItem.idToStitchingModel().get( tileId ) );

						final Tile< M > groupedTileCast = inputSolveItem.tileToGroupedTile().get( t );

						solveItem.tileToGroupedTile().put( t, groupedTileCast );
						solveItem.groupedTileToTiles().putIfAbsent( groupedTileCast, inputSolveItem.groupedTileToTiles().get( groupedTile ) );
					}
				}

				// add the restart lookup
				//for ( final int z : inputSolveItem.restarts() )
				//	if ( z >= solveItem.blockData().minZ() && z <= solveItem.blockData().maxZ() )
				//		solveItem.restarts().add( z );

				// used for global solve outside
				for ( int z = solveItem.blockData().minZ(); z <= solveItem.blockData().maxZ(); ++z )
				{
					final HashSet< String > allTilesPerZ = inputSolveItem.blockData().zToTileId().get( z );

					if ( allTilesPerZ == null )
						continue;

					final HashSet< String > myTilesPerZ = new HashSet<>();

					for ( final String tileId : allTilesPerZ )
					{
						if ( solveItem.idToTileMap().containsKey( tileId ) )
							myTilesPerZ.add( tileId );
					}
					
					if (myTilesPerZ.isEmpty())
					{
						LOG.info( "block " + solveItem.blockData().getId() + ": ERROR: z=" + z + " of new graph has 0 tileIds, the must not happen, this is a bug." );
						System.exit( 0 );
					}

					solveItem.blockData().zToTileId().put( z, myTilesPerZ );
				}

				solveItems.add( solveItem );
			}
		}
		return solveItems;
	}

	protected void solve(
			final AffineBlockDataWrapper< M, S, F > solveItem,
			final int numThreads ) throws InterruptedException, ExecutionException
	{
		final PreAlign preAlign = solveItem.blockData().solveTypeParameters().preAlign();

		final List<Double> blockOptimizerLambdasRigid = solveItem.blockData().solveTypeParameters().blockOptimizerLambdasRigid();
		final List<Double> blockOptimizerLambdasTranslation = solveItem.blockData().solveTypeParameters().blockOptimizerLambdasTranslation();
		final List<Double> blockOptimizerLambdasRegularization = solveItem.blockData().solveTypeParameters().blockOptimizerLambdasRegularization();
		final List<Integer> blockOptimizerIterations = solveItem.blockData().solveTypeParameters().blockOptimizerIterations();
		final List<Integer> blockMaxPlateauWidth = solveItem.blockData().solveTypeParameters().blockMaxPlateauWidth();
		final double blockMaxAllowedError = blockData.solveTypeParameters().blockMaxAllowedError();

		final TileConfiguration tileConfig = new TileConfiguration();

		// new HashSet because all tiles link to their common group tile, which is therefore present more than once
		tileConfig.addTiles( new HashSet<>( solveItem.tileToGroupedTile().values() ) );

		LOG.info("block " + solveItem.blockData().getId() + ": run: optimizing {} tiles", solveItem.groupedTileToTiles().keySet().size() );

		//final HashMap< Tile< ? >, Double > tileToDynamicLambda = SolveTools.computeMetaDataLambdas( tileConfig.getTiles(), solveItem, zRadiusRestarts, excludeFromRegularization, dynamicLambdaFactor );

		if ( preAlign == PreAlign.RIGID )
			LOG.info( "block " + solveItem.blockData().getId() + ": prealigning with rigid" );
		else if ( preAlign == PreAlign.TRANSLATION )
			LOG.info( "block " + solveItem.blockData().getId() + ": prealigning with translation" );
		else
			LOG.info( "block " + solveItem.blockData().getId() + ": NO prealignment" );

		for ( final Tile< ? > tile : tileConfig.getTiles() )
		{
			((InterpolatedAffineModel2D)((InterpolatedAffineModel2D)((InterpolatedAffineModel2D) tile.getModel()).getA()).getA()).setLambda( 1.0 ); // rigid vs affine

			if ( preAlign == PreAlign.RIGID )
				((InterpolatedAffineModel2D)((InterpolatedAffineModel2D) tile.getModel()).getA()).setLambda( 0.0 ); // translation vs (rigid vs affine)
			else if ( preAlign == PreAlign.TRANSLATION )
				((InterpolatedAffineModel2D)((InterpolatedAffineModel2D) tile.getModel()).getA()).setLambda( 1.0 ); // translation vs (rigid vs affine)

			((InterpolatedAffineModel2D) tile.getModel()).setLambda( 0.0 ); // prealign without regularization
		}
		
		try {
			double[] errors = SolveTools.computeErrors( tileConfig.getTiles() );
			LOG.info( "errors: " + errors[ 0 ] + "/" + errors[ 1 ] + "/" + errors[ 2 ] );

			if ( preAlign != PreAlign.NONE )
			{
				final Map< Tile< ? >, Integer > tileToZ = new HashMap<>();
	
				for ( final Tile< ? > tile : tileConfig.getTiles() )
					tileToZ.put( tile, (int)Math.round( solveItem.blockData().rtsc().getTileIdToSpecMap().get( solveItem.tileToIdMap().get( solveItem.groupedTileToTiles().get( tile ).get( 0 ) ) ).getZ() ) );
	
				SolveTools.preAlignByLayerDistance( tileConfig, tileToZ );
				//tileConfig.preAlign();

				errors = SolveTools.computeErrors( tileConfig.getTiles() );
				LOG.info( "errors: " + errors[ 0 ] + "/" + errors[ 1 ] + "/" + errors[ 2 ] );
			}
			// TODO: else they should be in the right position
		} catch (final NotEnoughDataPointsException | IllDefinedDataPointsException e) {
			LOG.info("block " + solveItem.blockData().getId() + ": prealign failed: ", e);
		}

		LOG.info( "block " + solveItem.blockData().getId() + ": lambda's used (rigid, translation, regularization):" );
	
		for ( int l = 0; l < blockOptimizerLambdasRigid.size(); ++l )
		{
			LOG.info( "block " + solveItem.blockData().getId() + ": l=" + 
					blockOptimizerLambdasRigid.get( l ) + ", " +
					blockOptimizerLambdasTranslation.get( l ) + ", " +
					blockOptimizerLambdasRegularization.get( l ) );
		}

		for ( int s = 0; s < blockOptimizerIterations.size(); ++s )
		{
			// TODO: has to be generic, we do not know how deep the interpolated models are at compile time
			// TODO: so it also has to be defined by the SolveSetFactory:
			//		blockOptimizerLambdasRigid, blockOptimizerLambdasTranslation, tileToDynamicLambda
			//
			//		blockOptimizerIterations
			//		blockMaxPlateauWidth
			//
			//		length of all arrays needs to be equal

			final double lambdaRigid = blockOptimizerLambdasRigid.get( s );
			final double lambdaTranslation = blockOptimizerLambdasTranslation.get( s );
			final double lambdaRegularization = blockOptimizerLambdasRegularization.get( s );

			for (final Tile< ? > tile : tileConfig.getTiles() )
			{
				((InterpolatedAffineModel2D)((InterpolatedAffineModel2D)((InterpolatedAffineModel2D) tile.getModel()).getA()).getA()).setLambda( lambdaRigid);
				((InterpolatedAffineModel2D)((InterpolatedAffineModel2D) tile.getModel()).getA()).setLambda( lambdaTranslation );
				((InterpolatedAffineModel2D) tile.getModel()).setLambda( lambdaRegularization ); // dynamic
			}

			final int numIterations = blockOptimizerIterations.get( s );
			final int maxPlateauWidth = blockMaxPlateauWidth.get( s );

			LOG.info( "block " + solveItem.blockData().getId() + ": l(rigid)=" + lambdaRigid + ", l(translation)=" + lambdaTranslation + ": l(regularization)=" + lambdaRegularization + ", numIterations=" + numIterations + ", maxPlateauWidth=" + maxPlateauWidth );

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

		final double[] errors = SolveTools.computeErrors(tileConfig.getTiles() );
		LOG.info( "errors: " + errors[ 0 ] + "/" + errors[ 1 ] + "/" + errors[ 2 ] );

		//
		// create lookup for the new models
		//
		solveItem.blockData().idToNewModel().clear();

		final ArrayList< String > tileIds = new ArrayList<>();
		final HashMap< String, AffineModel2D > tileIdToGroupModel = new HashMap<>();

		for ( final Tile< ? > tile : solveItem.tileToGroupedTile().keySet() )
		{
			final String tileId = solveItem.tileToIdMap().get( tile );

			tileIds.add( tileId );
			tileIdToGroupModel.put( tileId, SolveTools.createAffine(solveItem.tileToGroupedTile().get(tile ).getModel()) );
		}

		Collections.sort( tileIds );

		for (final String tileId : tileIds )
		{
			final AffineModel2D affine = solveItem.idToStitchingModel().get( tileId ).copy();

			affine.preConcatenate( tileIdToGroupModel.get( tileId ) );

			LOG.info("block " + solveItem.blockData().getId() + ": grouped model for tile {} is {}", tileId, tileIdToGroupModel.get( tileId ));

			solveItem.blockData().idToNewModel().put( tileId, affine );
			LOG.info("block " + solveItem.blockData().getId() + ": tile {} model from grouped tile is {}", tileId, affine);
		}
	}

	// note: these are local errors of a single block only
	protected void computeSolveItemErrors( final AffineBlockDataWrapper< M, S, F > solveItem, final ArrayList< CanvasMatches > canvasMatches )
	{
		LOG.info( "Computing per-block errors for " + solveItem.blockData().rtsc().getTileCount() + " tiles using " + canvasMatches.size() + " pairs of images ..." );

		// for local fits
		final Model< ? > crossLayerModel = new InterpolatedAffineModel2D<>( new AffineModel2D(), new RigidModel2D(), 0.25 );
		//final Model< ? > montageLayerModel = solveItem.stitchingSolveModelInstance();

		for ( final CanvasMatches match : canvasMatches )
		{
			final String pTileId = match.getpId();
			final String qTileId = match.getqId();

			final TileSpec pTileSpec = solveItem.blockData().rtsc().getTileSpec( pTileId );
			final TileSpec qTileSpec = solveItem.blockData().rtsc().getTileSpec( qTileId );

			// it is from a different solveitem
			if ( pTileSpec == null || qTileSpec == null )
				continue;

			// for a correct computation of errors after global alignment
			//if ( serializeMatches )
			//	solveItem.matches().add( new SerializableValuePair<>(new SerializableValuePair<>(pTileId, qTileId ), match.getMatches() ) );

			final double vDiff = WorkerTools.computeAlignmentError(
					crossLayerModel,
					solveItem.blockData().solveTypeParameters().stitchingSolveModelInstance( (int)Math.round( pTileSpec.getZ() ) ),
					pTileSpec,
					qTileSpec,
					solveItem.blockData().idToNewModel().get( pTileId ),
					solveItem.blockData().idToNewModel().get( qTileId ),
					match.getMatches() );

			solveItem.blockData().idToBlockErrorMap()
					.computeIfAbsent(pTileId, k -> new ArrayList<>())
					.add(new SerializableValuePair<>(qTileId, vDiff));
			solveItem.blockData().idToBlockErrorMap()
					.computeIfAbsent(qTileId, k -> new ArrayList<>())
					.add(new SerializableValuePair<>(pTileId, vDiff));
		}

		LOG.info( "computeSolveItemErrors, exit" );
	}

	/**
	 * Adaptation of {@link Tile#traceConnectedGraph} that avoids StackOverflowError from
	 * too much recursion when dealing with larger connected graphs.
	 */
	@SuppressWarnings("JavadocReference")
	private void safelyTraceConnectedGraph(final Tile<?> forTile,
										   final Set<Tile<?>> graph,
										   final Set<Tile<?>> deferredTiles,
										   final int recursionDepth) {
		final int maxRecursionDepth = 500;

		graph.add(forTile);

		for (final Tile<?> t : forTile.getConnectedTiles()) {
			if (! (graph.contains(t) || deferredTiles.contains(t))) {
				if (recursionDepth < maxRecursionDepth) {
					safelyTraceConnectedGraph(t, graph, deferredTiles, recursionDepth + 1);
				} else {
					deferredTiles.add(t);
				}
			}
		}
	}

	/**
	 * Adaptation of {@link Tile#identifyConnectedGraphs} that avoids StackOverflowError from
	 * too much recursion when dealing with larger connected graphs.
	 */
	private ArrayList< Set< Tile< ? > > > safelyIdentifyConnectedGraphs(final Collection<Tile<?>> tiles) {

		LOG.info("safelyIdentifyConnectedGraphs: entry, checking {} tiles", tiles.size());

		final ArrayList< Set< Tile< ? > > > graphs = new ArrayList<>();
		int numInspectedTiles = 0;
		A:		for ( final Tile< ? > tile : tiles )
		{
			for ( final Set< Tile< ? > > knownGraph : graphs ) {
				if (knownGraph.contains(tile)) {
					continue A;
				}
			}

			final Set< Tile< ? > > currentGraph = new HashSet<>();
			final Set< Tile< ? > > deferredTiles = new HashSet<>();
			safelyTraceConnectedGraph(tile, currentGraph, deferredTiles, 0);

			while (!deferredTiles.isEmpty()) {
				LOG.info("safelyIdentifyConnectedGraphs: {} max recursion deferred tiles, current graph size is {}",
						 deferredTiles.size(), currentGraph.size());
				final List<Tile<?>> toDoList = new ArrayList<>(deferredTiles);
				deferredTiles.clear();
				for (final Tile<?> toDoTile : toDoList) {
					safelyTraceConnectedGraph(toDoTile, currentGraph, deferredTiles, 0);
				}
			}

			numInspectedTiles += currentGraph.size();
			graphs.add(currentGraph);

			if ( numInspectedTiles == tiles.size() ) {
				break;
			}
		}

		LOG.info("safelyIdentifyConnectedGraphs: returning {} graph(s)", graphs.size());

		return graphs;
	}

	private static final Logger LOG = LoggerFactory.getLogger(Worker.class);
}

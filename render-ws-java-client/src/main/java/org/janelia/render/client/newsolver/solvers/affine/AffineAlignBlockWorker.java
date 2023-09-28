package org.janelia.render.client.newsolver.solvers.affine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.DoubleSummaryStatistics;
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
import org.janelia.alignment.spec.ResolvedTileSpecsWithMatchPairs;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.assembly.ResultContainer;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMAlignmentParameters;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMAlignmentParameters.PreAlign;
import org.janelia.render.client.newsolver.solvers.Worker;
import org.janelia.render.client.newsolver.solvers.WorkerTools;
import org.janelia.render.client.newsolver.solvers.WorkerTools.LayerDetails;
import org.janelia.render.client.parameter.BlockOptimizerParameters;
import org.janelia.render.client.parameter.BlockOptimizerParameters.AlignmentModelType;
import org.janelia.render.client.solver.ConstantAffineModel2D;
import org.janelia.render.client.solver.Graph;
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

public class AffineAlignBlockWorker<M extends Model<M> & Affine2D<M>, S extends Model<S> & Affine2D<S>> extends Worker<AffineModel2D, FIBSEMAlignmentParameters<M, S>>
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

	final AffineBlockDataWrapper<M, S> inputSolveItem;
	private ArrayList<BlockData<AffineModel2D, FIBSEMAlignmentParameters<M, S>>> result;

	// to filter matches
	final MatchFilter matchFilter;

	// if stitching first should be done
	final boolean stitchFirst;


	// created by SolveItemData.createWorker()
	public AffineAlignBlockWorker(
			final BlockData<AffineModel2D, FIBSEMAlignmentParameters<M, S>> blockData,
			final int startId,
			final int numThreads )
	{
		super( startId, blockData, numThreads );

		final FIBSEMAlignmentParameters<M, S> parameters = blockData.solveTypeParameters();
		this.matchDataClient = new RenderDataClient(parameters.baseDataUrl(),
													parameters.matchOwner(),
													parameters.matchCollection());

		this.inputSolveItem = new AffineBlockDataWrapper<>(blockData);

		if (parameters.maxNumMatches() <= 0)
			this.matchFilter = new NoMatchFilter();
		else
			this.matchFilter = new RandomMaxAmountFilter(parameters.maxNumMatches());

		// used locally
		this.stitchFirst = (parameters.minStitchingInliersSupplier() != null);
		this.pairs = new ArrayList<>();
		this.zToPairs = new HashMap<>();

		// NOTE: if you choose to stitch first, you need to pre-align, otherwise, it's OK to use the initial alignment for each tile
		if (stitchFirst && parameters.preAlign() == PreAlign.NONE) {
			LOG.error("Since you choose to stitch first, you must pre-align with Translation or Rigid.");
			throw new RuntimeException("Since you choose to stitch first, you must pre-align with Translation or Rigid.");
		}
	}

	@Override
	public ArrayList<BlockData<AffineModel2D, FIBSEMAlignmentParameters<M, S>>> getBlockDataList()
	{
		return result;
	}

	@Override
	public void run() throws IOException, ExecutionException, InterruptedException, NoninvertibleModelException
	{
		// for error computation (local)
		final List<CanvasMatches> canvasMatches = assembleMatchData();

		// minStitchingInliersSupplier:
		// how many stitching inliers are needed to stitch first
		// reason: if tiles are rarely connected and it is stitched first, a useful
		// common alignment model after stitching cannot be found
		stitchSectionsAndCreateGroupedTiles(inputSolveItem, pairs, zToPairs, stitchFirst, numThreads);

		connectGroupedTiles(pairs, inputSolveItem);

		final List<AffineBlockDataWrapper<M, S>> solveItems = splitSolveItem(inputSolveItem, startId);

		for ( final AffineBlockDataWrapper<M, S> solveItem : solveItems)
		{
				/*
				java.lang.NullPointerException: Cannot invoke "org.janelia.alignment.spec.TileSpec.getZ()" because the return value of "org.janelia.alignment.spec.ResolvedTileSpecCollection.getTileSpec(String)" is null
						at org.janelia.render.client.newsolver.solvers.affine.AffineAlignBlockWorker.assignRegularizationModel(AffineAlignBlockWorker.java:345)
						at org.janelia.render.client.newsolver.solvers.affine.AffineAlignBlockWorker.run(AffineAlignBlockWorker.java:164)
						at org.janelia.render.client.newsolver.DistributedAffineBlockSolver.lambda$1(DistributedAffineBlockSolver.java:108)
						at java.base/java.util.concurrent.FutureTask.run(FutureTask.java:264)
						at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)
						at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)
						at java.base/java.lang.Thread.run(Thread.java:833)
				*/

			if ( !assignRegularizationModel( solveItem, AffineBlockDataWrapper.samplesPerDimension, stabilizationRadius ) )
				throw new RuntimeException( "Couldn't regularize. Please check." );

			solve( solveItem, numThreads );
		}

		for ( final AffineBlockDataWrapper<M, S> solveItem : solveItems)
			computeSolveItemErrors(solveItem.blockData(), canvasMatches);

		// clean up
		this.result = new ArrayList<>();
		for ( final AffineBlockDataWrapper<M, S> solveItem : solveItems)
		{
			result.add( solveItem.blockData() );
			solveItem.matches().clear();
			solveItem.tileToGroupedTile().clear();
			solveItem.groupedTileToTiles().clear();
			solveItem.idToTileMap().clear();
		}
	}

	private List<CanvasMatches> assembleMatchData()
			throws IOException {

		final BlockData<AffineModel2D, FIBSEMAlignmentParameters<M, S>> blockData = inputSolveItem.blockData();

		Integer maxZDistance = blockData.solveTypeParameters().maxZRangeMatches(); // TODO: move this parameter to API query
		if (maxZDistance < 0) {
			maxZDistance = null;
		}

		final String matchCollectionName = matchDataClient.getProject(); // TODO: remove matchDataClient

		LOG.info("assembleMatchData: entry, loading transforms and matches for block {} with {} tiles and maxZDistance={}",
				 blockData, blockData.getTileCount(), maxZDistance);

		final ResolvedTileSpecsWithMatchPairs tileSpecsWithMatchPairs =
				renderDataClient.getResolvedTilesWithMatchPairs(renderStack,
																blockData.getBounds(),
																matchCollectionName,
																null,
																null);
		// TODO: only pull rtsc once and initialize result after that (currently done in BlockData constructor)

		// block tileIds are filtered by center point during BlockFactory.defineBlockCollection process
		// (more specifically by BlockFactory.pruneRtsc), so apply the same filtering to retrieved tile and match data
		final Set<String> tileIdsToKeep = new HashSet<>(blockData.rtsc().getTileIds());
		tileSpecsWithMatchPairs.normalize(tileIdsToKeep, maxZDistance);

		final List<CanvasMatches> matchPairs = new ArrayList<>(tileSpecsWithMatchPairs.getMatchPairCount());

		for (final CanvasMatches pair : tileSpecsWithMatchPairs.getMatchPairs()) {

			final String pId = pair.getpId();
			final TileSpec pTileSpec = tileSpecsWithMatchPairs.getTileSpec(pId);
			final Tile<M> p = getOrBuildTile(pId, pTileSpec);

			final String qId = pair.getqId();
			final TileSpec qTileSpec = tileSpecsWithMatchPairs.getTileSpec(qId);
			final Tile<M> q = getOrBuildTile(qId, qTileSpec);

			// remember the entries, need to perform section-based stitching before running global optimization
			pairs.add(
					new ValuePair<>(
							new ValuePair<>(p, q),
							matchFilter.filter(pair.getMatches(),
											   pTileSpec,
											   qTileSpec)
					)
			);//CanvasMatchResult.convertMatchesToPointMatchList(match.getMatches()) ) );

			final int pZ = pTileSpec.getIntegerZ();
			final int qZ = qTileSpec.getIntegerZ();

			// if the pair is from the same layer we remember the current index in the pairs list for stitching
			if (pZ == qZ) {
				zToPairs.computeIfAbsent(pZ, k -> new ArrayList<>()).add(pairs.size() - 1);
			}

			// for error computation
			matchPairs.add(pair);
		}

		return matchPairs;
	}

	protected Tile<M> getOrBuildTile(final String id, final TileSpec tileSpec)
	{
		if (inputSolveItem.idToTileMap().containsKey(id))
			return inputSolveItem.idToTileMap().get(id);

		final Tile<M> tile;
		final Pair<Tile<M>, AffineModel2D> pair =
				SolveTools.buildTileFromSpec(inputSolveItem.blockData().solveTypeParameters().blockSolveModel().copy(), AffineBlockDataWrapper.samplesPerDimension, tileSpec);
		tile = pair.getA();
		inputSolveItem.idToTileMap().put(id, tile);
		inputSolveItem.tileToIdMap().put(tile, id);

		inputSolveItem.idToPreviousModel().put(id, pair.getB());

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
	@SuppressWarnings("SameParameterValue")
	protected boolean assignRegularizationModel(
			final AffineBlockDataWrapper<M, S> solveItem,
			final int samplesPerDimension,
			final int stabilizationRadius )
	{
		LOG.info("assignRegularizationModel: entry, block {}", solveItem.blockData);

		final HashMap< Integer, List<Tile<M>> > zToGroupedTileList = new HashMap<>();

		// new HashSet because all tiles link to their common group tile, which is therefore present more than once
		Tile< M > currentTile = null;
		for ( final Tile< M > groupedTile : new HashSet<>( solveItem.tileToGroupedTile().values() ) )
		{
			try
			{
				currentTile = solveItem.groupedTileToTiles().get(groupedTile).get(0);
				final int z = (int)Math.round(
								solveItem.blockData().rtsc().getTileSpec(solveItem.tileToIdMap().get(currentTile)).getZ());

				zToGroupedTileList.putIfAbsent(z, new ArrayList<>());
				zToGroupedTileList.get(z).add(groupedTile);
			}
			catch (final Exception e) {
				final String currentTileId = solveItem.tileToIdMap().get(currentTile);
				throw new RuntimeException("failed to to populate zToGroupedTileList for block " + solveItem.blockData +
										   ", currentTileId is " + currentTileId, e);
			}
		}
		
		final ArrayList<Integer> allZ = new ArrayList<>(zToGroupedTileList.keySet());
		Collections.sort(allZ);

		final AlignmentModel model = (AlignmentModel) zToGroupedTileList.get(allZ.get(0)).get(0).getModel();
		final Model<?> regularizer = model.getModel(AlignmentModelType.REGULARIZATION.name());

		if (regularizer instanceof ConstantAffineModel2D) {
			// it is based on ConstantAffineModels, meaning we extract metadata and use that as regularizer
			assignConstantAffineModel(solveItem, samplesPerDimension, allZ, zToGroupedTileList);
			LOG.info("assignRegularizationModel: exit, block {}", solveItem.blockData);

		} else if (regularizer instanceof StabilizingAffineModel2D) {
			// it is based on StabilizingAffineModel2Ds, meaning each image wants to sit where its corresponding one in the above layer sits
			assignStabilizingAffineModel(solveItem, samplesPerDimension, stabilizationRadius, allZ, zToGroupedTileList);
			LOG.info("assignRegularizationModel: exit, block {}", solveItem.blockData);

		} else {
			LOG.info("Not using ConstantAffineModel2D for regularization. Nothing to do in assignRegularizationModel().");
		}

		return true;
	}

	private static <M extends Model<M> & Affine2D<M>, S extends Model<S> & Affine2D<S>> void assignConstantAffineModel(
			final AffineBlockDataWrapper<M, S> solveItem,
			final int samplesPerDimension,
			final ArrayList<Integer> allZ,
			final HashMap<Integer, List<Tile<M>>> zToGroupedTileList) {

		for (final int z : allZ) {
			final List<Tile<M>> groupedTiles = zToGroupedTileList.get(z);

			LOG.info("assignConstantAffineModel: z={} contains {} grouped tiles (ConstantAffineModel2D)", z, groupedTiles.size());

			// find out where the Tile sits on average (given the n tiles it is grouped from)
			for (final Tile<M> groupedTile : groupedTiles) {
				final List<Tile<M>> imageTiles = solveItem.groupedTileToTiles().get(groupedTile);

				if (groupedTiles.size() > 1)
					LOG.info("assignConstantAffineModel: z={} grouped tile [{}] contains {} image tiles.",
							 z, groupedTile, imageTiles.size());

				// create pointmatches from the edges of each image in the grouped tile to the respective edges in the metadata
				final List<PointMatch> matches = new ArrayList<>();

				for (final Tile<M> imageTile : imageTiles) {
					final String tileId = solveItem.tileToIdMap().get(imageTile);
					final TileSpec tileSpec = solveItem.blockData().rtsc().getTileSpec(tileId);

					final AffineModel2D stitchingTransform = solveItem.idToStitchingModel().get(tileId);
					final AffineModel2D metaDataTransform = getMetaDataTransformation(solveItem, tileId);

					final double sampleWidth = (tileSpec.getWidth() - 1.0) / (samplesPerDimension - 1.0);
					final double sampleHeight = (tileSpec.getHeight() - 1.0) / (samplesPerDimension - 1.0);

					// ALTERNATIVELY: ONLY SELECT ONE OF THE TILES
					for (int y = 0; y < samplesPerDimension; ++y) {
						final double sampleY = y * sampleHeight;
						for (int x = 0; x < samplesPerDimension; ++x) {
							final double[] p = new double[] { x * sampleWidth, sampleY };
							final double[] q = new double[] { x * sampleWidth, sampleY };

							stitchingTransform.applyInPlace(p);
							metaDataTransform.applyInPlace(q);

							matches.add(new PointMatch(new Point(p), new Point(q)));
						}
					}
				}

				final AlignmentModel model = (AlignmentModel) groupedTile.getModel();
				final ConstantAffineModel2D<?, ?> cModel = (ConstantAffineModel2D<?, ?>) model.getModel(AlignmentModelType.REGULARIZATION.name());
				final Model<?> regularizationModel = cModel.getModel();

				try {
					regularizationModel.fit(matches);
					double sumError = 0;

					for (final PointMatch pm : matches) {
						pm.getP1().apply(regularizationModel);
						final double distance = Point.distance(pm.getP1(), pm.getP2());
						sumError += distance;
					}
					LOG.info("assignConstantAffineModel: Error={}", (sumError / matches.size()));
				} catch (final Exception e) {
					LOG.info("assignConstantAffineModel: Caught exception: ", e);
				}
			}
		}
	}

	private static <M extends Model<M> & Affine2D<M>, S extends Model<S> & Affine2D<S>> void assignStabilizingAffineModel(
			final AffineBlockDataWrapper<M, S> solveItem,
			final int samplesPerDimension,
			final int stabilizationRadius,
			final ArrayList<Integer> allZ,
			final HashMap<Integer, List<Tile<M>>> zToGroupedTileList) {

		for (int i = 0; i < allZ.size(); ++i) {
			final int z = allZ.get(i);

			// first get all tiles from adjacent layers and the associated grouped tile
			final ArrayList<LayerDetails<M>> neighboringTiles = new ArrayList<>();

			int from = i, to = i;
			for (int d = 1; d <= stabilizationRadius && i + d < allZ.size(); ++d ) {
				neighboringTiles.addAll(WorkerTools.layerDetails(allZ, zToGroupedTileList, solveItem, i + d));
				to = i + d;
			}

			for (int d = 1; d <= stabilizationRadius && i - d >= 0; ++d) {
				// always connect up, even if it is a restart, then break afterwards
				neighboringTiles.addAll(WorkerTools.layerDetails(allZ, zToGroupedTileList, solveItem, i - d));
				from = i - d;
			}

			final List<Tile<M>> groupedTiles = zToGroupedTileList.get(z);
			LOG.info("assignStabilizingModel: z={} contains {} grouped tiles (StabilizingAffineModel2D), connected from {} to {}",
					 z, groupedTiles.size(), allZ.get(from), allZ.get(to));

			// now go over all tiles of the current z
			for (final Tile<M> groupedTile : groupedTiles) {
				final List<Tile<M>> imageTiles = solveItem.groupedTileToTiles().get(groupedTile);

				if (groupedTiles.size() > 1)
					LOG.info("assignStabilizingModel: z={} grouped tile [{}] contains {} image tiles.",
							 z, groupedTile, imageTiles.size());

				// create pointmatches from the edges of each image in the grouped tile to the respective edges in the metadata
				// TODO: create a custom class for this type
				final List<Pair<List<PointMatch>, ? extends Tile<?>>> matchesList = new ArrayList<>();

				for (final Tile<M> imageTile : imageTiles) {
					final String tileId = solveItem.tileToIdMap().get(imageTile);
					final TileSpec tileSpec = solveItem.blockData().rtsc().getTileSpec(tileId);

					final int tileCol = tileSpec.getLayout().getImageCol();// tileSpec.getImageCol();
					final int tileRow = tileSpec.getLayout().getImageRow();

					final ArrayList<LayerDetails<M>> neighbors = new ArrayList<>();

					for (final LayerDetails<M> neighboringTile : neighboringTiles)
						if (neighboringTile.tileCol == tileCol && neighboringTile.tileRow == tileRow)
							neighbors.add(neighboringTile);

					if (neighbors.isEmpty()) {
						// this can happen when number of tiles per layer changes for example
						LOG.info("assignStabilizingModel: could not find corresponding tile for {}", tileId);
						continue;
					}

					for (final LayerDetails<M> neighbor : neighbors) {
						final AffineModel2D stitchingTransform = solveItem.idToStitchingModel().get(tileId);
						final AffineModel2D stitchingTransformPrev = solveItem.idToStitchingModel().get(neighbor.tileId);

						final List<PointMatch> matches = SolveTools.createFakeMatches(
								tileSpec.getWidth(),
								tileSpec.getHeight(),
								stitchingTransform, // p
								stitchingTransformPrev, // q
								samplesPerDimension);

						matchesList.add(new ValuePair<>(matches, neighbor.prevGroupedTile));
					}
				}

				// in every iteration, update q with the current group tile transformation(s), the fit p to q for regularization
				final AlignmentModel model = (AlignmentModel) groupedTile.getModel();
				final StabilizingAffineModel2D<?> regularizationModel = (StabilizingAffineModel2D<?>) model.getModel(AlignmentModelType.REGULARIZATION.name());
				regularizationModel.setFitData(matchesList);
			}
		}
	}

	/**
	 * How to compute the metadata transformation, for now just using the previous transform
	 * 
	 * @param solveItem - which solveitem
	 * @param tileId - which TileId
	 * @return - AffineModel2D with the metadata transformation for this tile
	 */
	protected static AffineModel2D getMetaDataTransformation( final AffineBlockDataWrapper<?, ?> solveItem, final String tileId )
	{
		return solveItem.idToPreviousModel().get( tileId );
	}

	protected void connectGroupedTiles(
			final ArrayList< Pair< Pair< Tile< ? >, Tile< ? > >, List< PointMatch > > > pairs,
			final AffineBlockDataWrapper<M, S> solveItem )
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
			final AffineBlockDataWrapper<M, S> solveItem,
			final ArrayList<Pair<Pair<Tile<?>, Tile<?>>, List<PointMatch>>> pairs,
			final HashMap<Integer, List<Integer>>zToPairs,
			final boolean stitchFirst,
			final int numThreads)
	{
		final BlockData<AffineModel2D, FIBSEMAlignmentParameters<M, S>> blockData = solveItem.blockData();
		final int maxPlateauWidthStitching = blockData.solveTypeParameters().maxPlateauWidthStitching();
		final double maxAllowedErrorStitching = blockData.solveTypeParameters().maxAllowedErrorStitching();
		final int maxIterationsStitching = blockData.solveTypeParameters().maxIterationsStitching();
		final Function<Integer, Integer> minStitchingInliersSupplier = blockData.solveTypeParameters().minStitchingInliersSupplier();

		// combine tiles per layer that are be stitched first, but iterate over all z's 
		// (also those only consisting of single tiles, they are connected in z though)
		final ArrayList<Integer> zList = new ArrayList<>(blockData.getResults().getZLayers());
		Collections.sort( zList );

		for ( final int z : zList )
		{
			LOG.info("stitchSectionsAndCreateGroupedTiles: block {}, z={}", blockData, z);

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

						final Tile< S > p, q;
		
						if ( !idTotile.containsKey( pId ) )
						{
							//p = new Tile<>( model.copy() );
							// since we do preAlign later this seems redundant. However, it makes sure the tiles are more or less at the right global coordinates
							p = SolveTools.buildTile(
									solveItem.idToPreviousModel().get( pId ),
									blockData.solveTypeParameters().stitchingSolveModelInstance(z).copy(),
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
									blockData.solveTypeParameters().stitchingSolveModelInstance(z).copy(),
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
			for (final String tileId : blockData.getResults().getTileIdsForZLayer(z))
				if ( !idTotile.containsKey( tileId ) )
				{
					LOG.info("stitchSectionsAndCreateGroupedTiles: block {}, unconnected tileId {}", blockData, tileId);

					final Tile<S> tile = new Tile<>(blockData.solveTypeParameters().stitchingSolveModelInstance(z).copy());
					idTotile.put( tileId, tile );
					tileToId.put( tile, tileId );
				}

			// Now identify connected graphs within all tiles
			final ArrayList<Set<Tile<?>>> sets = WorkerTools.safelyIdentifyConnectedGraphs(new ArrayList<>(idTotile.values()));

			LOG.info("stitchSectionsAndCreateGroupedTiles: block {}, z={}, #sets={}", blockData, z, sets.size());

			// solve each set (if size > 1)
			int setCount = 0;
			for ( final Set< Tile< ? > > set : sets )
			{
				setCount++;
				LOG.info("stitchSectionsAndCreateGroupedTiles: block {}: Set={}", blockData, setCount);

				//
				// the grouped tile for this set of one layer
				//
				final Tile<M> groupedTile = new Tile<>(blockData.solveTypeParameters().blockSolveModel().copy());

				if ( set.size() > 1 )
				{
					final TileConfiguration tileConfig = new TileConfiguration();
					tileConfig.addTiles( set );

					// we always prealign (not sure how far off the current alignment in renderer is)
					// a simple preAlign suffices for Translation and Rigid as it doesn't matter which Tile is fixed during alignment
					try {
						tileConfig.preAlign();
					} catch (final NotEnoughDataPointsException | IllDefinedDataPointsException e) {
						LOG.info("stitchSectionsAndCreateGroupedTiles: block {}: Could not solve prealign for z={}, cause: ",
								 blockData, z, e);
					}

					// test if the graph has cycles, if yes we would need to do a solve
					if ( !( (
							set.iterator().next().getModel() instanceof TranslationModel2D ||
							set.iterator().next().getModel() instanceof RigidModel2D) &&
							!new Graph( new ArrayList<>( set ) ).isCyclic() ) )
					{
						LOG.info("stitchSectionsAndCreateGroupedTiles: block {}: Full solve required for stitching z={}",
								 blockData, z);

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

							LOG.info("stitchSectionsAndCreateGroupedTiles: block {}: Solve z={} avg={}, min={}, max={}",
									 blockData, z, tileConfig.getError(), tileConfig.getMinError(), tileConfig.getMaxError());
						} catch (final Exception e) {
							LOG.info("stitchSectionsAndCreateGroupedTiles: block {}: Could not solve stitiching for z={}, cause: ",
									 blockData, z, e);
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

						LOG.info("stitchSectionsAndCreateGroupedTiles: block {}: TileId {} Model=     {}", blockData, tileId, affine);
						LOG.info("stitchSectionsAndCreateGroupedTiles: block {}: TileId {} prev Model={}", blockData, tileId, solveItem.idToPreviousModel().get(tileId));
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
							final ImagePlus imp1 = VisualizeTools.renderTS(models, blockData.rtsc().getTileIdToSpecMap(), 0.15);
							imp1.setTitle( "z=" + z );
						} catch (final NoninvertibleModelException e) {
							LOG.info("stitchSectionsAndCreateGroupedTiles: Could not show section: ", e);
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

					LOG.info("stitchSectionsAndCreateGroupedTiles: block {}: Single TileId {}", blockData, tileId);
				}
			}
		}
	}

	protected ArrayList<AffineBlockDataWrapper<M, S>> splitSolveItem(final AffineBlockDataWrapper<M, S> inputSolveItem, final int startId)
	{
		// assigning new id's to the solve items (they collide for now with other workers, fix upon merging)
		int id = startId + 1;

		final ArrayList<AffineBlockDataWrapper<M, S>> solveItems = new ArrayList<>();

		// new HashSet because all tiles link to their common group tile, which is therefore present more than once
		final ArrayList<Set<Tile<?>>> graphs = WorkerTools.safelyIdentifyConnectedGraphs(new HashSet<>(inputSolveItem.tileToGroupedTile().values()));

		final BlockData<AffineModel2D, FIBSEMAlignmentParameters<M, S>> blockData = inputSolveItem.blockData();
		LOG.info("splitSolveItem: block {}: Graph consists of {} subgraphs.", blockData, graphs.size());

		if (graphs.isEmpty())
		{
			throw new RuntimeException( "Something went wrong. The inputsolve item has 0 subgraphs. stopping." );
		}
		else if ( graphs.size() == 1 )
		{
			solveItems.add( inputSolveItem );

			LOG.info("splitSolveItem: block {}: Graph 0 has {} tiles.", blockData, graphs.get(0).size());
		}
		else
		{
			int graphCount = 0;

			for ( final Set< Tile< ? > > subgraph : graphs ) // TODO: type sets properly
			{
				graphCount++;
				LOG.info("splitSolveItem: block {}: new graph {} has {} tiles.", blockData, graphCount, subgraph.size());

				// re-assemble allTileIds and idToTileSpec
				// update all the maps
				final ResolvedTileSpecCollection originalRTSC = blockData.rtsc();
				final List<TileSpec> groupedTileSpecList = subgraph.stream()
						.map(groupedTile -> inputSolveItem.groupedTileToTiles().get(groupedTile))
						.flatMap(Collection::stream)
						.map(tile -> inputSolveItem.tileToIdMap().get(tile))
						.map(originalRTSC::getTileSpec)
						.collect(Collectors.toList());

				final ResolvedTileSpecCollection newRTSC =
						new ResolvedTileSpecCollection(originalRTSC.getTransformSpecs(),
													   groupedTileSpecList);

				final BlockData<AffineModel2D, FIBSEMAlignmentParameters<M, S>> newBlockData = new BlockData<>(blockData.blockFactory(), // no copy necessary
																											   blockData.solveTypeParameters(), // no copy necessary
																											   id,
																											   blockData.getBounds(),
																											   newRTSC);
				final AffineBlockDataWrapper<M, S> solveItem = new AffineBlockDataWrapper<>(newBlockData);

				++id;

				LOG.info("splitSolveItem: block {}: old graph id={}, new graph id={}", newBlockData, blockData.getId(), newBlockData.getId());
				LOG.info("splitSolveItem: block {}: min: {} > max: {}", newBlockData, newBlockData.minZ(), newBlockData.maxZ());

				// update all the maps
				for ( final Tile< ? > groupedTile : subgraph )
				{
					for ( final Tile< M > t : inputSolveItem.groupedTileToTiles().get( groupedTile ) )
					{
						final String tileId = inputSolveItem.tileToIdMap().get( t );
		
						solveItem.idToTileMap().put( tileId, t );
						solveItem.tileToIdMap().put( t, tileId );
						solveItem.idToPreviousModel().put( tileId, inputSolveItem.idToPreviousModel().get( tileId ) );
						newBlockData.getResults().recordModel(tileId, blockData.getResults().getModelFor(tileId));

						solveItem.idToStitchingModel().put(tileId, inputSolveItem.idToStitchingModel().get(tileId));

						final Tile< M > groupedTileCast = inputSolveItem.tileToGroupedTile().get( t );

						solveItem.tileToGroupedTile().put( t, groupedTileCast );
						solveItem.groupedTileToTiles().putIfAbsent( groupedTileCast, inputSolveItem.groupedTileToTiles().get( groupedTile ) );
					}
				}

				solveItems.add(solveItem);
			}
		}
		return solveItems;
	}

	protected void solve(
			final AffineBlockDataWrapper<M, S> solveItem,
			final int numThreads ) throws InterruptedException, ExecutionException
	{
		final BlockData<AffineModel2D, FIBSEMAlignmentParameters<M, S>> blockData = solveItem.blockData();
		final PreAlign preAlign = blockData.solveTypeParameters().preAlign();

		final List<Integer> blockOptimizerIterations = blockData.solveTypeParameters().blockOptimizerIterations();
		final List<Integer> blockMaxPlateauWidth = blockData.solveTypeParameters().blockMaxPlateauWidth();
		final double blockMaxAllowedError = this.blockData.solveTypeParameters().blockMaxAllowedError();

		final TileConfiguration tileConfig = new TileConfiguration();

		// new HashSet because all tiles link to their common group tile, which is therefore present more than once
		tileConfig.addTiles( new HashSet<>( solveItem.tileToGroupedTile().values() ) );

		if (LOG.isInfoEnabled()) {
			final DoubleSummaryStatistics errors = SolveTools.computeErrors(tileConfig.getTiles());
			LOG.info("solve: block {}, optimizing {} tiles with preAlign {}, error stats before optimization are {}",
					 blockData, solveItem.groupedTileToTiles().keySet().size(), preAlign, errors);
		}

		if ((preAlign != null) && (preAlign != PreAlign.NONE)) {
			preAlign(solveItem, tileConfig, preAlign.toString());
		}

		final BlockOptimizerParameters blockOptimizer = blockData.solveTypeParameters().blockOptimizerParameters();
		for (int k = 0; k < blockOptimizerIterations.size(); ++k) {

			final Map<String, Double> weights = blockOptimizer.getWeightsForRun(k);
			LOG.info("solve: block {}, run {}: l(rigid)={}, l(translation)={}, l(regularization)={}",
					solveItem.blockData().getId(), k, weights.get(AlignmentModelType.RIGID.name()), weights.get(AlignmentModelType.TRANSLATION.name()), weights.get(AlignmentModelType.REGULARIZATION.name()));

			for (final Tile<?> tile : tileConfig.getTiles()) {
				final AlignmentModel model = (AlignmentModel) tile.getModel();
				model.setWeights(weights);
			}

			final int numIterations = blockOptimizerIterations.get(k);
			final int maxPlateauWidth = blockMaxPlateauWidth.get(k);

			final ErrorStatistic observer = new ErrorStatistic(maxPlateauWidth + 1);
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
					numThreads);
		}

		if (LOG.isInfoEnabled()) {
			final DoubleSummaryStatistics errors = SolveTools.computeErrors(tileConfig.getTiles());
			LOG.info("solve: errors: {}", errors);
		}

		final ArrayList<String> tileIds = new ArrayList<>();
		final HashMap<String, AffineModel2D> tileIdToGroupModel = new HashMap<>();

		for (final Tile<?> tile : solveItem.tileToGroupedTile().keySet()) {
			final String tileId = solveItem.tileToIdMap().get(tile);

			tileIds.add(tileId);
			final AlignmentModel model = (AlignmentModel) solveItem.tileToGroupedTile().get(tile).getModel();
			tileIdToGroupModel.put(tileId, model.createAffineModel2D());
		}

		Collections.sort( tileIds );

		for (final String tileId : tileIds )
		{
			final AffineModel2D affine = solveItem.idToStitchingModel().get( tileId ).copy();

			affine.preConcatenate( tileIdToGroupModel.get( tileId ) );

			LOG.info("solve: block {}: grouped model for tile {} is {}", blockData, tileId, tileIdToGroupModel.get(tileId));

			blockData.getResults().recordModel(tileId, affine);
			LOG.info("solve: block {}: tile {} model from grouped tile is {}", blockData, tileId, affine);
		}
	}

	private void preAlign(
			final AffineBlockDataWrapper<?, ?> solveItem,
			final TileConfiguration tileConfig,
			final String preAlignModelKey) {

		final BlockOptimizerParameters blockOptimizer = blockData.solveTypeParameters().blockOptimizerParameters();

		for (final Tile<?> tile : tileConfig.getTiles()) {
			final Map<String, Double> weights = blockOptimizer.setUpZeroWeights();
			weights.put(preAlignModelKey, 1.0);
			final AlignmentModel model = (AlignmentModel) tile.getModel();
			model.setWeights(weights);
		}

		try {
			final Map<Tile<?>, Integer> tileToZ = new HashMap<>();
			for (final Tile<?> tile : tileConfig.getTiles()) {
				final Tile<?> firstTile = solveItem.groupedTileToTiles().get(tile).get(0);
				final String firstTileId = solveItem.tileToIdMap().get(firstTile);
				final int z = (int) Math.round(solveItem.blockData().rtsc().getTileIdToSpecMap().get(firstTileId).getZ());
				tileToZ.put(tile, z);
			}
			SolveTools.preAlignByLayerDistance(tileConfig, tileToZ);

			if (LOG.isInfoEnabled()) {
				final DoubleSummaryStatistics errors = SolveTools.computeErrors(tileConfig.getTiles());
				LOG.info("preAlign: errors: {}", errors);
			}
			// TODO: else they should be in the right position
		} catch (final NotEnoughDataPointsException | IllDefinedDataPointsException e) {
			LOG.info("block {}: prealign failed: ", inputSolveItem.blockData(), e);
		}
	}

	// note: these are local errors of a single block only
	private void computeSolveItemErrors(final BlockData<AffineModel2D, FIBSEMAlignmentParameters<M, S>> blockData,
										final List<CanvasMatches> canvasMatches) {

		LOG.info("computeSolveItemErrors: entry, computing per-block errors for {} tiles using {} pairs of images ...",
				 blockData.rtsc().getTileCount(), canvasMatches.size());

		// for local fits
		final Model< ? > crossLayerModel = new InterpolatedAffineModel2D<>( new AffineModel2D(), new RigidModel2D(), 0.25 );

		final ResultContainer<AffineModel2D> results = blockData.getResults();

		for (final CanvasMatches match : canvasMatches) {

			final String pTileId = match.getpId();
			final String qTileId = match.getqId();

			final TileSpec pTileSpec = blockData.rtsc().getTileSpec(pTileId);
			final TileSpec qTileSpec = blockData.rtsc().getTileSpec(qTileId);

			// it is from a different solveitem
			if (pTileSpec == null || qTileSpec == null)
				continue;

			final double vDiff = WorkerTools.computeAlignmentError(
					crossLayerModel,
					blockData.solveTypeParameters().stitchingSolveModelInstance(pTileSpec.getZ().intValue()),
					pTileSpec,
					qTileSpec,
					results.getModelFor(pTileId),
					results.getModelFor(qTileId),
					match.getMatches());

			results.recordPairwiseTileError(pTileId, qTileId, vDiff);
		}

		LOG.info("computeSolveItemErrors, exit");
	}

	private static final Logger LOG = LoggerFactory.getLogger(Worker.class);
}

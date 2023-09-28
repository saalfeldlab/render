package org.janelia.render.client.newsolver.assembly;

import mpicbg.models.ErrorStatistic;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TileUtil;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.assembly.matches.SameTileMatchCreator;
import org.janelia.render.client.newsolver.setup.DistributedSolveParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

public class BlockSolver<Z, G extends Model<G>, R> {

	final private G globalModel;
	final private SameTileMatchCreator<R> sameTileMatchCreator;

	final private int maxPlateauWidth;
	final private double maxAllowedError;
	final private int maxIterations;
	final private int numThreads;

	public BlockSolver(
			final G globalModel,
			final SameTileMatchCreator<R> sameTileMatchCreator,
			final DistributedSolveParameters parameters
	) {
		this.globalModel = globalModel;
		this.sameTileMatchCreator = sameTileMatchCreator;
		this.maxPlateauWidth = parameters.maxPlateauWidthGlobal;
		this.maxAllowedError = parameters.maxAllowedErrorGlobal;
		this.maxIterations = parameters.maxIterationsGlobal;
		this.numThreads = parameters.threadsGlobal;
	}

	public G globalSolveModel() {
		return globalModel;
	}

	public HashMap<BlockData<R, ?>, Tile<G>> globalSolve(
			final List<? extends BlockData<R, ?>> blocks,
			final ResultContainer<Z> globalResults
	) throws NotEnoughDataPointsException, IllDefinedDataPointsException, InterruptedException, ExecutionException {

		final HashMap<BlockData<R, ?>, Tile<G>> blockToTile = new HashMap<>();
		for (final BlockData<R, ?> block : blocks) {
			blockToTile.put(block, new Tile<>(globalModel.copy()));
			globalResults.addTileSpecs(block.rtsc().getTileSpecs());
		}

		LOG.info("globalSolve: solving {} items", blocks.size());
		final Set<? extends BlockData<R, ?>> otherBlocks = new HashSet<>(blocks);
		final TileConfiguration tileConfigBlocks = new TileConfiguration();

		for (final BlockData<R, ?> solveItemA : blocks) {
			LOG.info("globalSolve: solveItemA xy range is {}", solveItemA.boundingBox());
			otherBlocks.remove(solveItemA);

			// tilespec is identical for all overlapping blocks
			final ResolvedTileSpecCollection tileSpecs = solveItemA.rtsc();

			for (final BlockData<R, ?> solveItemB : otherBlocks) {
				LOG.info("globalSolve: solveItemB xy range is {}",solveItemB.boundingBox());

				final Set<String> commonTileIds = getCommonTileIds(solveItemA, solveItemB);
				final List<PointMatch> matchesAtoB = new ArrayList<>();

				for (final String tileId : commonTileIds) {
					final TileSpec tileSpecAB = tileSpecs.getTileSpec(tileId);

					final R modelA = solveItemA.getResults().getIdToModel().get(tileId);
					final R modelB = solveItemB.getResults().getIdToModel().get(tileId);
					if (modelA == null)  {
						throw new IllegalArgumentException("model A is missing for tile " + tileId);
					} else if (modelB == null)  {
						throw new IllegalArgumentException("model B is missing for tile " + tileId);
					}
					sameTileMatchCreator.addMatches(tileSpecAB, modelA, modelB, solveItemA, solveItemB, matchesAtoB);
				}

				// connect global tiles and mark for optimization
				final Tile<G> tileA = blockToTile.get(solveItemA);
				final Tile<G> tileB = blockToTile.get(solveItemB);
				tileA.connect(tileB, matchesAtoB);
				tileConfigBlocks.addTile(tileA);
				tileConfigBlocks.addTile(tileB);
			}
		}

		LOG.info("launching Pre-Align, tileConfigBlocks has {} tiles and {} fixed tiles",
				  tileConfigBlocks.getTiles().size(), tileConfigBlocks.getFixedTiles().size());

		tileConfigBlocks.preAlign();

		LOG.info("Optimizing ... ");
		final float damp = 1.0f;
		TileUtil.optimizeConcurrently(
				new ErrorStatistic(maxPlateauWidth + 1),
				maxAllowedError,
				maxIterations,
				maxPlateauWidth,
				damp,
				tileConfigBlocks,
				tileConfigBlocks.getTiles(),
				tileConfigBlocks.getFixedTiles(),
				numThreads);

		return blockToTile;
	}

	protected static Set<String> getCommonTileIds(
			final BlockData<?, ?> blockA,
			final BlockData<?, ?> blockB
	) {
		final Set<String> tileIdsA = new HashSet<>(blockA.getResults().getIdToModel().keySet());
		final Set<String> tileIdsB = blockB.getResults().getIdToModel().keySet();
		tileIdsA.retainAll(tileIdsB);
		return tileIdsA;
	}

	private static final Logger LOG = LoggerFactory.getLogger(BlockSolver.class);
}

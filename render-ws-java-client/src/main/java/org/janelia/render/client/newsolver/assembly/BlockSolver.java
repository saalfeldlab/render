package org.janelia.render.client.newsolver.assembly;

import mpicbg.models.ErrorStatistic;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TileUtil;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import org.apache.commons.lang.math.IntRange;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.assembly.matches.SameTileMatchCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.geom.Rectangle2D;
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

	// local structures required for solving
	// TODO: this is too nested, simplify
	final public HashMap<
			Integer,
			ArrayList<
					Pair<Pair<BlockData<?, R, ?>, BlockData<?, R, ?>>, HashSet<String>>>>
			zToBlockPairs = new HashMap<>();

	public BlockSolver(
			final G globalModel,
			final SameTileMatchCreator<R> sameTileMatchCreator,
			final int maxPlateauWidth,
			final double maxAllowedError,
			final int maxIterations,
			final int numThreads
	) {
		this.globalModel = globalModel;
		this.sameTileMatchCreator = sameTileMatchCreator;
		this.maxPlateauWidth = maxPlateauWidth;
		this.maxAllowedError = maxAllowedError;
		this.maxIterations = maxIterations;
		this.numThreads = numThreads;
	}

	public G globalSolveModel() {
		return globalModel;
	}

	public HashMap<BlockData<?, R, ?>, Tile<G>> globalSolve(
			final List<? extends BlockData<?, R, ?>> blocks,
			final AssemblyMaps<Z> am
	) throws NotEnoughDataPointsException, IllDefinedDataPointsException, InterruptedException, ExecutionException {
		
		final HashMap<BlockData<?, R, ?>, Tile<G>> blockToTile = new HashMap<>();
		blocks.forEach(block -> blockToTile.put(block, new Tile<>(globalModel.copy())));

		LOG.info("globalSolve: solving {} items", blocks.size());
		final Set<? extends BlockData<?, R, ?>> otherBlocks = new HashSet<>(blocks);
		final TileConfiguration tileConfigBlocks = new TileConfiguration();

		for (final BlockData<?, R, ?> solveItemA : blocks) {
			LOG.info("globalSolve: solveItemA xy range is {}", solveItemA.boundingBox());
			otherBlocks.remove(solveItemA);

			for (final BlockData<?, R, ?> solveItemB : otherBlocks) {
				LOG.info("globalSolve: solveItemB xy range is {}", solveItemB.boundingBox());

				// TODO: is the loop over z really necessary here?
				// TODO: does this work if more than two blocks overlap? common tile ids are added for each block pair
				final int[] overlapZRange = computeZOverlap(solveItemA, solveItemB);
				for (final int z : overlapZRange) {
					final HashSet<String> commonTileIds = getCommonTileIds(z, solveItemA, solveItemB);
					final HashSet<String> assignedTileIds = am.zToTileIdGlobal.computeIfAbsent(z, k -> new HashSet<>());
					assignedTileIds.addAll(commonTileIds);

					// tilespec is identical for blockA and blockB
					final ResolvedTileSpecCollection tileSpecs = solveItemA.rtsc();
					final List<PointMatch> matchesAtoB = new ArrayList<>();

					for (final String tileId : commonTileIds) {
						final TileSpec tileSpecAB = tileSpecs.getTileSpec(tileId);
						am.idToTileSpecGlobal.put(tileId, tileSpecAB);

						final R modelA = solveItemA.idToNewModel().get(tileId);
						final R modelB = solveItemB.idToNewModel().get(tileId);
						sameTileMatchCreator.addMatches(tileSpecAB, modelA, modelB, solveItemA, solveItemB, matchesAtoB);
					}

					// connect global tiles and mark for optimization
					final Tile<G> tileA = blockToTile.get(solveItemA);
					final Tile<G> tileB = blockToTile.get(solveItemB);
					tileA.connect(tileB, matchesAtoB);
					tileConfigBlocks.addTile(tileA);
					tileConfigBlocks.addTile(tileB);

					// remember which solveItems defined which tileIds of this z section
					zToBlockPairs.computeIfAbsent(z, k -> new ArrayList<>())
							.add(new ValuePair<>(new ValuePair<>(solveItemA, solveItemB), commonTileIds));

					// non-common tiles
					final HashSet<String> unmatchedTileIds = new HashSet<>(solveItemA.zToTileId().get(z));
					unmatchedTileIds.removeAll(commonTileIds);
					unmatchedTileIds.removeAll(assignedTileIds);

					// store them in relevant collections
					for (final String tileId : unmatchedTileIds) {
						assignedTileIds.add(tileId);
						am.idToTileSpecGlobal.put(tileId, tileSpecs.getTileSpec(tileId));
						am.zToTileIdGlobal.computeIfAbsent(z, k -> new HashSet<>()).add(tileId);
					}
					// TODO: no DummyBlocks anymore, just set it to null, let's see how to fix it down the road -> use solveItemA?
					zToBlockPairs.get(z).add(new ValuePair<>(new ValuePair<>(solveItemA, null), unmatchedTileIds));
				}
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

	private int[] computeZOverlap(final BlockData<?, R, ?> blockA, final BlockData<?, R, ?> blockB) {
		final Bounds boundsA = boundsFrom(blockA.boundingBox());
		final Bounds boundsB = boundsFrom(blockB.boundingBox());
		final Rectangle2D xyOverlap = boundsA.toRectangle().createIntersection(boundsB.toRectangle());

		if (xyOverlap.isEmpty())
			return new int[0];

		final IntRange zRangeA = new IntRange(blockA.minZ(), blockA.maxZ());
		final IntRange zRangeB = new IntRange(blockB.minZ(), blockB.maxZ());

		if (!zRangeA.overlapsRange(zRangeB))
			return new int[0];

		final IntRange zOverlap = new IntRange(Math.max(zRangeA.getMinimumInteger(), zRangeB.getMinimumInteger()),
											   Math.min(zRangeA.getMaximumInteger(), zRangeB.getMaximumInteger()));

		return zOverlap.toArray();
	}

	private Bounds boundsFrom(final Pair<double[], double[]> minMax) {
		final double[] min = minMax.getA();
		final double[] max = minMax.getB();
		return new Bounds(min[0], min[1], min[2], max[0], max[1], max[2]);
	}

	protected static HashSet<String> getCommonTileIds(
			final int z,
			final BlockData<?, ?, ?> blockA,
			final BlockData<?, ?, ?> blockB
	) {
		final HashSet<String> tileIdsA = new HashSet<>(blockA.zToTileId().get(z));
		final HashSet<String> tileIdsB = blockB.zToTileId().get(z);
		if (tileIdsB != null)
			tileIdsA.retainAll(tileIdsB);
		return tileIdsA;
	}

	private static final Logger LOG = LoggerFactory.getLogger(BlockSolver.class);
}

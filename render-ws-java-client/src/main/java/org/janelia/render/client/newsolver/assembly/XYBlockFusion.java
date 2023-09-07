package org.janelia.render.client.newsolver.assembly;

import mpicbg.models.Model;
import mpicbg.models.Tile;
import net.imglib2.util.Pair;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.newsolver.BlockData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

public class XYBlockFusion<Z, I, G extends Model<G>, R> implements BlockFusion<Z, G, R>
{
	final BlockSolver<Z, G, R> solver;
	final BiFunction<R, G, I> combineResultGlobal;
	final BiFunction<List<I>, List<Double>, Z> fusion;

	public XYBlockFusion(
			final BlockSolver<Z, G, R> solver,
			final BiFunction<R, G, I> combineResultGlobal,
			final BiFunction<List<I>, List<Double>, Z> fusion
	) {
		this.solver = solver;
		this.combineResultGlobal = combineResultGlobal;
		this.fusion = fusion;
	}

	@Override
	public void globalFusion(
			final List<? extends BlockData<?, R, ?>> blocks,
			final AssemblyMaps<Z> am, 
			final HashMap<BlockData<?, R, ?>, Tile<G>> blockToTile
	) {
		final HashMap<BlockData<?, R, ?>, G> blockToG = new HashMap<>();

		blockToTile.forEach((block, tile) -> {
			if (block != null)
				blockToG.put(block, tile.getModel());
		});

		final ArrayList<Integer> zSections = new ArrayList<>(am.zToTileIdGlobal.keySet());
		Collections.sort(zSections);

		final Map<BlockData<?, R, ?>, WeightFunction> blockToWeightFunctions = new HashMap<>();

		for (final int z : zSections) {
			// for every z section, tileIds might be provided from different overlapping blocks if they were not connected and have been split
			final ArrayList<Pair<Pair<BlockData<?, R, ?>, BlockData<?, R, ?>>, HashSet<String>>> blockPairsAndTileIdsForZLayer =
					solver.zToBlockPairs.get(z);

			for (final Pair<Pair<BlockData<?, R, ?>, BlockData<?, R, ?>>, HashSet<String>> blockPairAndTileId : blockPairsAndTileIdsForZLayer) {

				final Pair<BlockData<?, R, ?>, BlockData<?, R, ?>> blockPair = blockPairAndTileId.getA();
				final Set<String> tileIds = blockPairAndTileId.getB();

				BlockData<?, R, ?> blockA = blockPair.getA();
				BlockData<?, R, ?> blockB = blockPair.getB();

				final WeightFunction weightA = (blockA == null) ? new EmptyWeightFunction()
						: blockToWeightFunctions.computeIfAbsent(blockA, BlockData::createWeightFunctions);
				final WeightFunction weightB = (blockB == null) ? new EmptyWeightFunction()
						: blockToWeightFunctions.computeIfAbsent(blockB, BlockData::createWeightFunctions);

				final int idA = (blockA == null) ? -1 : blockA.getId();
				final int idB = (blockB == null) ? -1 : blockB.getId();

				// take care of null blocks which occur if a tile is only in one block
				if (blockA == null && blockB == null)
					throw new RuntimeException("Both blocks are null, this must not happen: z = " + z);

				if (blockA == null)
					blockA = blockB;
				else if (blockB == null)
					blockB = blockA;

				final G globalModelA = blockToG.get(blockA);
				final G globalModelB = blockToG.get(blockB);

				for (final String tileId : tileIds) {
					final TileSpec tile = blockA.rtsc().getTileSpec(tileId);
					final double[] midpointXY = tile.getWorldCoordinates((tile.getWidth() - 1) / 2.0, (tile.getHeight() - 1) / 2.0);

					final double wA = weightA.compute(midpointXY[0], midpointXY[1], z);
					final double wB = weightB.compute(midpointXY[0], midpointXY[1], z);
					if (wA == 0 && wB == 0)
						throw new RuntimeException("Two block with weight 0, this must not happen: " + idA + ", " + idB);
					final double regularizeB = wB / (wA + wB);

					final I modelA = combineResultGlobal.apply(blockA.idToNewModel().get(tileId), globalModelA);
					final I modelB = combineResultGlobal.apply(blockB.idToNewModel().get(tileId), globalModelB);

					final Z tileModel = fusion.apply(
							new ArrayList<>(Arrays.asList(modelA, modelB)),
							new ArrayList<>(Arrays.asList(1.0 - regularizeB, regularizeB)));
					am.idToFinalModelGlobal.put(tileId, tileModel);
					LOG.info("z=" + z + ": " + idA + "-" + wA + " ----- " + idB + "-" + wB + " ----regB=" + regularizeB);

					// TODO: proper error computation using the matches that are now stored in the SolveItemData object
					// works, because a null solveItem always has a weight of 0
					if (regularizeB < 0.5)
						am.idToErrorMapGlobal.put(tileId, blockA.idToBlockErrorMap().get(tileId));
					else
						am.idToErrorMapGlobal.put(tileId, blockB.idToBlockErrorMap().get(tileId));
				}
			}
		}
	}

	private static class EmptyWeightFunction implements WeightFunction {
		@Override
		public double compute(final double x, final double y, final double z) {
			return 0.0;
		}
	}

	private static final Logger LOG = LoggerFactory.getLogger(XYBlockFusion.class);
}

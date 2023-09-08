package org.janelia.render.client.newsolver.assembly;

import mpicbg.models.Model;
import mpicbg.models.Tile;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.solver.SerializableValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

public class BlockCombiner<Z, I, G extends Model<G>, R> {

	final BlockSolver<Z, G, R> solver;
	final BiFunction<R, G, I> combineResultGlobal;
	final BiFunction<List<I>, List<Double>, Z> fusion;

	public BlockCombiner(
			final BlockSolver<Z, G, R> solver,
			final BiFunction<R, G, I> combineResultGlobal,
			final BiFunction<List<I>, List<Double>, Z> fusion
	) {
		this.solver = solver;
		this.combineResultGlobal = combineResultGlobal;
		this.fusion = fusion;
	}

	public void fuseGlobally(
			final AssemblyMaps<Z> am,
			final HashMap<BlockData<?, R, ?>, Tile<G>> blockToTile
	) {
		final HashMap<BlockData<?, R, ?>, G> blockToG = new HashMap<>();

		blockToTile.forEach((block, tile) -> {
			if (block != null)
				blockToG.put(block, tile.getModel());
		});

		final Map<String, List<BlockData<?, R, ?>>> tileIdToBlocks = new HashMap<>();
		for (final BlockData<?, R, ?> block : blockToTile.keySet()) {
			for (final String tileId : block.rtsc().getTileIds()) {
				tileIdToBlocks.computeIfAbsent(tileId, k -> new ArrayList<>()).add(block);
			}
		}

		final Map<BlockData<?, R, ?>, WeightFunction> blockToWeightFunctions = new HashMap<>();
		for (final Map.Entry<String, List<BlockData<?, R, ?>>> entry : tileIdToBlocks.entrySet()) {
			final String tileId = entry.getKey();
			final List<BlockData<?, R, ?>> blocksForTile = entry.getValue();
			final int[] blockIds = blocksForTile.stream().mapToInt(BlockData::getId).toArray();
			LOG.info("tile '" + tileId + "' is in following blocks: " + Arrays.toString(blockIds));

			// all tilespecs are identical for all overlapping blocks
			final TileSpec tile = blocksForTile.get(0).rtsc().getTileSpec(tileId);
			final double[] midpointXY = tile.getWorldCoordinates((tile.getWidth() - 1) / 2.0, (tile.getHeight() - 1) / 2.0);
			final double z = tile.getZ();

			final List<I> models = new ArrayList<>();
			final List<Double> weights = new ArrayList<>();
			List<SerializableValuePair<String, Double>> error = null;
			double maxWeight = -1.0;
			for (final BlockData<?, R, ?> block : blocksForTile) {
				final G globalModel = blockToG.get(block);
				final I model = combineResultGlobal.apply(block.idToNewModel().get(tileId), globalModel);
				models.add(model);

				final WeightFunction weight = blockToWeightFunctions.computeIfAbsent(block, BlockData::createWeightFunction);
				final double w = weight.compute(midpointXY[0], midpointXY[1], z);
				weights.add(w);

				// TODO: proper error computation using the matches that are now stored in the SolveItemData object
				if (w > maxWeight) {
					maxWeight = w;
					error = block.idToBlockErrorMap().get(tileId);
				}
			}

			final List<Double> normalizedWeights = normalize(weights);
			LOG.info("tile '" + tileId + "', models are fused following weights: " + Arrays.toString(normalizedWeights.toArray()));
			final Z tileModel = fusion.apply(models, normalizedWeights);
			am.idToFinalModelGlobal.put(tileId, tileModel);
			am.idToErrorMapGlobal.put(tileId, error);
		}
	}

	private List<Double> normalize(final List<Double> weights) {
		final double sum = weights.stream().mapToDouble(Double::doubleValue).sum();
		return weights.stream().map(w -> w / sum).collect(Collectors.toList());
	}

	private static final Logger LOG = LoggerFactory.getLogger(BlockCombiner.class);
}

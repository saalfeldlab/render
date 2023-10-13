package org.janelia.render.client.newsolver.assembly;

import mpicbg.models.Model;
import mpicbg.models.Tile;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

public class BlockCombiner<Z, I, G extends Model<G>, R> {

	final BiFunction<R, G, I> combineResultGlobal;
	final BiFunction<List<I>, List<Double>, Z> fusion;

	public BlockCombiner(
			final BiFunction<R, G, I> combineResultGlobal,
			final BiFunction<List<I>, List<Double>, Z> fusion
	) {
		this.combineResultGlobal = combineResultGlobal;
		this.fusion = fusion;
	}

	public void fuseGlobally(
			final ResultContainer<Z> globalData,
			final HashMap<BlockData<R, ?>, Tile<G>> blockToTile,
			final BlockFactory blockFactory) {
		final HashMap<BlockData<R, ?>, G> blockToG = new HashMap<>();

		blockToTile.forEach((block, tile) -> {
			if (block != null)
				blockToG.put(block, tile.getModel());
		});

		final Map<String, List<BlockData<R, ?>>> tileIdToBlocks = new HashMap<>();
		final Map<BlockData<R, ?>, WeightFunction> blockToWeightFunctions = new HashMap<>();
		for (final BlockData<R, ?> block : blockToTile.keySet()) {
			final Set<String> blockTileIds = block.getResults().getTileIds();
			for (final String tileId : blockTileIds) {
				tileIdToBlocks.computeIfAbsent(tileId, k -> new ArrayList<>()).add(block);
			}
			if (blockTileIds.isEmpty()) {
				LOG.info("fuseGlobally: ignoring block {} because it has no tiles", block.toDetailsString());
			} else {
				blockToWeightFunctions.put(block, blockFactory.createWeightFunction(block));
			}
		}

		for (final Map.Entry<String, List<BlockData<R, ?>>> entry : tileIdToBlocks.entrySet()) {
			final String tileId = entry.getKey();
			final List<BlockData<R, ?>> blocksForTile = entry.getValue();
			// LOG.debug("fuseGlobally: tile '{}' is in following blocks: {}", tileId, blocksForTile);

			// all tileSpecs are identical for all overlapping blocks
			final TileSpec tile = blocksForTile.get(0).rtsc().getTileSpec(tileId);
			final double[] midpointXY = tile.getWorldCoordinates((tile.getWidth() - 1) / 2.0, (tile.getHeight() - 1) / 2.0);
			final double z = tile.getZ();

			final List<I> models = new ArrayList<>();
			final List<Double> weights = new ArrayList<>();
			Map<String, Double> error = null;
			double maxWeight = -1.0;
			for (final BlockData<R, ?> block : blocksForTile) {
				final G globalModel = blockToG.get(block);
				final R newModel = block.getResults().getModelFor(tileId);
				// TODO: confirm this is proper way to handle, consider moving retrieval to block method and put check there
				if (newModel == null) {
					throw new IllegalStateException("failed to find new model for tile " + tileId + " in block " + block);
				}
				final I model = combineResultGlobal.apply(newModel, globalModel);
				models.add(model);

				final WeightFunction weight = blockToWeightFunctions.get(block);
				if (weight == null) {
					throw new IllegalStateException("failed to find weight function for block " + block + " associated with tileId " + tileId);
				}
				final double w = weight.compute(midpointXY[0], midpointXY[1], z);
				weights.add(w);

				// TODO: proper error computation using the matches that are now stored in the SolveItemData object
				if (w > maxWeight) {
					maxWeight = w;
					error = block.getResults().getErrorMapFor(tileId);
				}
			}

			final List<Double> normalizedWeights = normalize(weights);
			LOG.debug("fuseGlobally: tile '{}' models are fused following weights: {}",
					  tileId, Arrays.toString(normalizedWeights.toArray()));
			final Z tileModel = fusion.apply(models, normalizedWeights);
			globalData.recordModel(tileId, tileModel);
			globalData.recordAllErrors(tileId, error);
		}
	}

	private List<Double> normalize(final List<Double> weights) {
		final double sum = weights.stream().mapToDouble(Double::doubleValue).sum();
		return weights.stream().map(w -> w / sum).collect(Collectors.toList());
	}

	private static final Logger LOG = LoggerFactory.getLogger(BlockCombiner.class);
}

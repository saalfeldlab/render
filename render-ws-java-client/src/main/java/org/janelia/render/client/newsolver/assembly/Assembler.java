package org.janelia.render.client.newsolver.assembly;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.newsolver.BlockData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mpicbg.models.Model;
import mpicbg.models.Tile;

/**
 * 
 * @author preibischs
 *
 * @param <Z> - the final output for each TileSpec
 * @param <G> - model used for global solve
 * @param <R> - the result from the block solves
 */
public class Assembler<Z, G extends Model<G>, R>
{
	final List<BlockData<R, ?>> blocks;
	final BlockSolver<Z, G, R> blockSolver;
	final BlockCombiner<Z, ?, G, R> blockCombiner;
	final Function<R, Z> converter;

	/**
	 * @param blocks - all individually computed blocks
	 * @param blockSolver - solver to use for the final assembly
	 * @param blockCombiner - fusion to use for the final assembly
	 * @param converter - a converter from R to Z - for the trivial case of a single block
	 */
	public Assembler(
			final List<BlockData<R, ?>> blocks,
			final BlockSolver<Z, G, R> blockSolver,
			final BlockCombiner<Z, ?, G, R> blockCombiner,
			final Function<R, Z> converter )
	{
		this.blocks = blocks;
		this.blockSolver = blockSolver;
		this.blockCombiner = blockCombiner;
		this.converter = converter;
	}

	public ResultContainer< Z > createAssembly()
	{
		// the trivial case of a single block, would crash with the code below
		if (isTrivialCase()) {
			return buildTrivialAssembly();
		}

		final ResultContainer<Z> results = new ResultContainer<>();

		// add shared transforms to assembly so that they can be used later when building resolved tile spec collections
		blocks.forEach(block -> results.addSharedTransforms(block.rtsc().getTransformSpecs()));

		try {
			// now compute the final alignment for each block
			final HashMap<BlockData<R, ?>, Tile<G>> blockToTile =
					blockSolver.globalSolve(blocks, results);

			// now fuse blocks into a full assembly
			blockCombiner.fuseGlobally(results, blockToTile);
		} catch (final Exception e) {
			throw new RuntimeException("failed assembly", e);
		}

		return results;
	}

	protected boolean isTrivialCase() {
		return blocks.size() == 1;
	}

	/**
	 * @return - the result of the trivial case
	 */
	private ResultContainer< Z > buildTrivialAssembly()
	{
		LOG.info("buildTrivialAssembly: entry, only a single block, no solve across blocks necessary.");

		final ResultContainer< Z > globalData = new ResultContainer<>();

		final BlockData<R, ?> solveItem = blocks.get( 0 );

		globalData.addSharedTransforms(solveItem.rtsc().getTransformSpecs());

		// TODO: why does this iterate over z values?
		for ( int z = solveItem.minZ(); z <= solveItem.maxZ(); ++z )
		{
			// there is no overlap with any other solveItem (should be beginning or end of the entire stack)
			final HashSet<String> tileIds = solveItem.getResults().getZLayerTileIds().get(z);

			// if there are none, we continue with the next
			if (tileIds.isEmpty())
				continue;

			final List<TileSpec> tileSpecs = tileIds.stream().map(id -> solveItem.rtsc().getTileSpec(id)).collect(Collectors.toList());
			globalData.addTileSpecs(tileSpecs);
			for (final String tileId : tileIds) {
				globalData.recordModel(tileId, converter.apply(solveItem.getResults().getIdToModel().get(tileId)));
			}
		}

		return globalData;
	}

	private static final Logger LOG = LoggerFactory.getLogger(Assembler.class);
}

package org.janelia.render.client.newsolver.assembly;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
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
	final GlobalSolver<G, R> globalSolver;
	final BlockCombiner<Z, ?, G, R> blockCombiner;
	final Function<R, Z> converter;

	/**
	 * @param globalSolver - solver to use for the final assembly
	 * @param blockCombiner - fusion to use for the final assembly
	 * @param converter - a converter from R to Z - for the trivial case of a single block
	 */
	public Assembler(
			final GlobalSolver<G, R> globalSolver,
			final BlockCombiner<Z, ?, G, R> blockCombiner,
			final Function<R, Z> converter )
	{
		this.globalSolver = globalSolver;
		this.blockCombiner = blockCombiner;
		this.converter = converter;
	}

	public ResultContainer<Z> createAssembly(final List<BlockData<R, ?>> blocks) {

		// the trivial case of a single block, would crash with the code below
		if (isTrivialCase(blocks)) {
			return buildTrivialAssembly(blocks.get(0));
		}

		final ResolvedTileSpecCollection cumulativeRtsc = mergeResolvedTileSpecCollections(blocks.stream().map(BlockData::rtsc).collect(Collectors.toList()));
		final ResultContainer<Z> results = new ResultContainer<>(cumulativeRtsc);

		try {
			// now compute the final alignment for each block
			final HashMap<BlockData<R, ?>, Tile<G>> blockToTile =
					globalSolver.globalSolve(blocks);

			// now fuse blocks into a full assembly
			blockCombiner.fuseGlobally(results, blockToTile);
		} catch (final Exception e) {
			throw new RuntimeException("failed assembly", e);
		}

		return results;
	}

	private static ResolvedTileSpecCollection mergeResolvedTileSpecCollections(final List<ResolvedTileSpecCollection> collections) {
		final Iterator<ResolvedTileSpecCollection> it = collections.iterator();
		final ResolvedTileSpecCollection first = it.next();

		final ResolvedTileSpecCollection cumulativeRtsc = new ResolvedTileSpecCollection(first.getTransformSpecs(), first.getTileSpecs());
		while (it.hasNext()) {
			cumulativeRtsc.merge(it.next());
		}

		return cumulativeRtsc;
	}

	protected boolean isTrivialCase(final List<BlockData<R, ?>> blocks) {
		return blocks.size() == 1;
	}

	/**
	 * @return - the result of the trivial case
	 */
	private ResultContainer<Z> buildTrivialAssembly(final BlockData<R, ?> block) {
		LOG.info("buildTrivialAssembly: entry, only a single block, no solve across blocks necessary.");

		final ResultContainer<Z> globalData = new ResultContainer<>(block.rtsc());
		for (final String tileId : block.rtsc().getTileIds()) {
			globalData.recordModel(tileId, converter.apply(block.getResults().getIdToModel().get(tileId)));
		}
		return globalData;
	}

	private static final Logger LOG = LoggerFactory.getLogger(Assembler.class);
}

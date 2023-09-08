package org.janelia.render.client.newsolver.assembly;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;

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
	final List<BlockData<?, R, ?>> blocks;
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
			final List<BlockData<?, R, ?>> blocks,
			final BlockSolver<Z, G, R> blockSolver,
			final BlockCombiner<Z, ?, G, R> blockCombiner,
			final Function<R, Z> converter )
	{
		this.blocks = blocks;
		this.blockSolver = blockSolver;
		this.blockCombiner = blockCombiner;
		this.converter = converter;
	}

	public AssemblyMaps< Z > createAssembly()
	{
		// the trivial case of a single block, would crash with the code below
		if (isTrivialCase()) {
			return buildTrivialAssembly();
		}

		final AssemblyMaps<Z> am = new AssemblyMaps<>();

		// add shared transforms to assembly so that they can be used later when building resolved tile spec collections
		blocks.forEach(block -> am.sharedTransformSpecs.addAll(block.rtsc().getTransformSpecs()));

		try {
			// now compute the final alignment for each block
			final HashMap<BlockData<?, R, ?>, Tile<G>> blockToTile =
					blockSolver.globalSolve(blocks, am);

			// now fuse blocks into a full assembly
			blockCombiner.fuseGlobally(am, blockToTile);
		} catch (final Exception e) {
			throw new RuntimeException("failed assembly", e);
		}

		return am;
	}

	//public abstract void globalSolve();
	//public abstract void assemble();

	protected boolean isTrivialCase() {
		return blocks.size() == 1;
	}

	/**
	 * @return - the result of the trivial case
	 */
	private AssemblyMaps< Z > buildTrivialAssembly()
	{
		LOG.info("buildTrivialAssembly: entry, only a single block, no solve across blocks necessary.");

		final AssemblyMaps< Z > globalData = new AssemblyMaps<>();

		final BlockData< ?, R, ?> solveItem = blocks.get( 0 );

		globalData.sharedTransformSpecs.addAll(solveItem.rtsc().getTransformSpecs());

		for ( int z = solveItem.minZ(); z <= solveItem.maxZ(); ++z )
		{
			// there is no overlap with any other solveItem (should be beginning or end of the entire stack)
			final HashSet< String > tileIds = solveItem.zToTileId().get( z );

			// if there are none, we continue with the next
			if (tileIds.isEmpty())
				continue;

			globalData.zToTileId.putIfAbsent(z, new HashSet<>());

			for ( final String tileId : tileIds )
			{
				globalData.zToTileId.get(z).add(tileId);
				globalData.idToTileSpec.put(tileId, solveItem.rtsc().getTileSpec(tileId));
				globalData.idToModel.put(tileId, converter.apply(solveItem.idToNewModel().get(tileId)));
			}
		}

		return globalData;
	}

	private static final Logger LOG = LoggerFactory.getLogger(Assembler.class);
}

package org.janelia.render.client.newsolver.assembly;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;

import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;
import org.janelia.render.client.newsolver.blockfactories.ZBlockFactory;
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
 * @param <F> - the blockFactory that was used
 */
public class Assembler< Z, G extends Model< G >, R, F extends BlockFactory< F > >
{
	final List<BlockData<?, R, ?, F> > blocks;
	final BlockSolver< Z, G, R, F > blockSolver;
	final BlockFusion< Z, G, R, F > blockFusion;
	final Function< R, Z > converter;

	/**
	 * @param blocks - all individually computed blocks
	 * @param blockSolver - solver to use for the final assembly
	 * @param blockFusion - fusion to use for the final assembly
	 * @param converter - a converter from R to Z - for the trivial case of a single block
	 */
	public Assembler(
			final List<BlockData<?, R, ?, F> > blocks,
			final BlockSolver< Z, G, R, F > blockSolver,
			final BlockFusion< Z, G, R, F > blockFusion,
			final Function< R, Z > converter )
	{
		this.blocks = blocks;
		this.blockSolver = blockSolver;
		this.blockFusion = blockFusion;
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
			final HashMap<BlockData<?, R, ?, F>, Tile<G>> blockToTile =
					blockSolver.globalSolve(blocks, am);

			// now fuse blocks into a full assembly
			blockFusion.globalFusion(blocks, am, blockToTile);
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

		final AssemblyMaps< Z > am = new AssemblyMaps<>();

		final BlockData< ?, R, ?, F > solveItem = blocks.get( 0 );

		am.sharedTransformSpecs.addAll(solveItem.rtsc().getTransformSpecs());

		for ( int z = solveItem.minZ(); z <= solveItem.maxZ(); ++z )
		{
			// there is no overlap with any other solveItem (should be beginning or end of the entire stack)
			final HashSet< String > tileIds = solveItem.zToTileId().get( z );

			// if there are none, we continue with the next
			if (tileIds.isEmpty())
				continue;

			am.zToTileIdGlobal.putIfAbsent( z, new HashSet<>() );

			for ( final String tileId : tileIds )
			{
				am.zToTileIdGlobal.get( z ).add( tileId );
				am.idToTileSpecGlobal.put( tileId, solveItem.rtsc().getTileSpec( tileId ) );
				am.idToFinalModelGlobal.put( tileId, converter.apply( solveItem.idToNewModel().get( tileId ) ) );
			}
		}

		return am;
	}

	private static final Logger LOG = LoggerFactory.getLogger(Assembler.class);
}

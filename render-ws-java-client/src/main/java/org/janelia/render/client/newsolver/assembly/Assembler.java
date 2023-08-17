package org.janelia.render.client.newsolver.assembly;

import java.util.HashSet;
import java.util.List;

import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Assembler< M, R, F extends BlockFactory< F > >
{
	final List<BlockData<?, R, ?, F> > blocks;
	final BlockSolver< M, R, F > blockSolver;

	/**
	 * @param blocks - all individually computed blocks
	 * @param startId - we may need to create DummyBlocks where z slices do not overlap with anything (beginning and end of stack)
	 */
	public Assembler(
			final List<BlockData<?, R, ?, F> > blocks,
			final BlockSolver< M, R, F > blockSolver )
	{
		this.blocks = blocks;
		this.blockSolver = blockSolver;
	}

	public AssemblyMaps< M > createAssembly()
	{
		AssemblyMaps< M > am;

		// the trivial case of a single block, would crash with the code below
		if ( ( am = handleTrivialCase() ) != null )
			return am;
		else
			am = new AssemblyMaps< M >();

		// now compute the final alignment for each block
		try
		{
			blockSolver.globalSolve( blocks, am );
		}
		catch ( Exception e)
		{
			e.printStackTrace();
		}

		//assemble();

		return am;
	}

	//public abstract void globalSolve();
	//public abstract void assemble();

	/**
	 * 
	 * @return - the result of the trivial case if it was a single block
	 */
	protected AssemblyMaps< M > handleTrivialCase()
	{
		if ( blocks.size() == 1 )
		{
			LOG.info( "Assembler: only a single block, no solve across blocks necessary." );

			final AssemblyMaps< M > am = new AssemblyMaps<>();

			final BlockData< ?, R, ?, F > solveItem = blocks.get( 0 );

			for ( int z = solveItem.minZ(); z <= solveItem.maxZ(); ++z )
			{
				// there is no overlap with any other solveItem (should be beginning or end of the entire stack)
				final HashSet< String > tileIds = solveItem.zToTileId().get( z );

				// if there are none, we continue with the next
				if ( tileIds.size() == 0 )
					continue;

				am.zToTileIdGlobal.putIfAbsent( z, new HashSet<>() );

				for ( final String tileId : tileIds )
				{
					am.zToTileIdGlobal.get( z ).add( tileId );
					am.idToTileSpecGlobal.put( tileId, solveItem.rtsc().getTileSpec( tileId ) );
					
					// TODO: Converter from R to M
					//am.idToFinalModelGlobal.put( tileId, solveItem.idToNewModel().get( tileId ) );
				}
			}

			return am;
		}
		else
		{
			return null;
		}
	}

	private static final Logger LOG = LoggerFactory.getLogger(Assembler.class);
}

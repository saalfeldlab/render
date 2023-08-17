package org.janelia.render.client.newsolver.assembly;

import java.util.HashSet;
import java.util.List;

import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.Model;

public class Assembler< M extends Model< M >, R extends CoordinateTransform, F extends BlockFactory< F > >
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

	public AssemblyMaps< R > createAssembly()
	{
		AssemblyMaps< R > am;

		// the trivial case of a single block, would crash with the code below
		if ( ( am = handleTrivialCase() ) != null )
			return am;
		else
			am = new AssemblyMaps< R >();

		// now compute the final alignment for each block
		try
		{
			blockSolver.globalSolve( blocks, am );
		}
		catch ( Exception e)
		{
			// TODO Auto-generated catch block
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
	protected AssemblyMaps< R > handleTrivialCase()
	{
		if ( blocks.size() == 1 )
		{
			LOG.info( "Assembler: only a single block, no solve across blocks necessary." );

			final AssemblyMaps< R > am = new AssemblyMaps< R >();

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
					am.idToFinalModelGlobal.put( tileId, solveItem.idToNewModel().get( tileId ) );
				}
			}

			return am;
		}
		else
		{
			return null;
		}
	}

	/*
	public static ResolvedTileSpecCollection combineAllTileSpecs( final List< BlockData<?, ?, ?, ?> > allItems )
	{
		final ResolvedTileSpecCollection rtsc = new ResolvedTileSpecCollection();

		// TODO trautmane - improve this
		// this should automatically get rid of duplicates due to the common tileId
		for ( BlockData<?, ?, ?, ?> block : allItems )
			block.rtsc().getTileSpecs().forEach( tileSpec -> rtsc.addTileSpecToCollection( tileSpec ) );

		return rtsc;
	}*/

	private static final Logger LOG = LoggerFactory.getLogger(Assembler.class);
}

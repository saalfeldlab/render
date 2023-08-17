package org.janelia.render.client.newsolver.assembly;

import java.util.List;

import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;

public interface BlockFusion< R, F extends BlockFactory< F > >
{
	public void globalFusion(
			List< ? extends BlockData<?, R, ?, F> > blocks,
			AssemblyMaps< ? > am );
}

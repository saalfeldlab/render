package org.janelia.render.client.newsolver.assembly;

import java.util.List;

import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;

import mpicbg.models.CoordinateTransform;

public interface BlockFusion< R extends CoordinateTransform, F extends BlockFactory< F > >
{
	public void globalFusion(
			List< ? extends BlockData<?, R, ?, F> > blocks,
			AssemblyMaps< ? > am );
}

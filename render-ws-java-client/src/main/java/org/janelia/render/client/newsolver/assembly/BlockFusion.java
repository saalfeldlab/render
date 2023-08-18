package org.janelia.render.client.newsolver.assembly;

import java.util.List;

import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;

import mpicbg.models.Model;

public interface BlockFusion< Z, G extends Model< G >, R, F extends BlockFactory< F > >
{
	public void globalFusion(
			List< ? extends BlockData<?, R, ?, F> > blocks,
			AssemblyMaps< Z > am );
}

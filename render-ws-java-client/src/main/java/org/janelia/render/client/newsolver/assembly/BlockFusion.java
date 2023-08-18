package org.janelia.render.client.newsolver.assembly;

import java.util.HashMap;
import java.util.List;

import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;
import org.janelia.render.client.newsolver.blockfactories.ZBlockFactory;

import mpicbg.models.Model;
import mpicbg.models.Tile;

public interface BlockFusion< Z, G extends Model< G >, R, F extends BlockFactory< F > >
{
	public void globalFusion(
			List< ? extends BlockData<?, R, ?, F> > blocks,
			AssemblyMaps< Z > am,
			HashMap< BlockData<?, R, ?, ZBlockFactory >, Tile< G > > blockToTile );
}

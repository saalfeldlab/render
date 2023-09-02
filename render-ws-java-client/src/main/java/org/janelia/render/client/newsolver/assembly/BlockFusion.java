package org.janelia.render.client.newsolver.assembly;

import java.util.HashMap;
import java.util.List;

import org.janelia.render.client.newsolver.BlockData;

import mpicbg.models.Model;
import mpicbg.models.Tile;

public interface BlockFusion<Z, G extends Model<G>, R>
{
	public void globalFusion(
			List<? extends BlockData<?, R, ?>> blocks,
			AssemblyMaps<Z> am,
			HashMap<BlockData<?, R, ?>, Tile<G>> blockToTile);
}

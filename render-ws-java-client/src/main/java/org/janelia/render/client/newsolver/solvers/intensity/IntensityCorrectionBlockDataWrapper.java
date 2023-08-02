package org.janelia.render.client.newsolver.solvers.intensity;

import mpicbg.models.Affine1D;
import mpicbg.models.Model;
import mpicbg.models.Tile;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMIntensityCorrectionParameters;

import java.util.HashMap;
import java.util.HashSet;

public class IntensityCorrectionBlockDataWrapper<M extends Model<M> & Affine1D<M>, F extends BlockFactory<F>> {
	final public static int samplesPerDimension = 2;

	final BlockData<M, FIBSEMIntensityCorrectionParameters<M>, F> blockData;

	//
	// local only variables needed for the block solve below
	//

	// all tiles, used for solving when not grouped
	final private HashMap<String, Tile<M>> idToTileMap = new HashMap<>();

	// used locally to map Tile back to TileId
	final private HashMap<Tile<M>, String> tileToIdMap = new HashMap<>();

	// which z layers are restarts
	final private HashSet<Integer> restarts = new HashSet<>();

	public IntensityCorrectionBlockDataWrapper(final BlockData<M, FIBSEMIntensityCorrectionParameters<M>, F> blockData) {
		this.blockData = blockData;
	}

	public BlockData<M, FIBSEMIntensityCorrectionParameters<M>, F>  blockData() { return blockData; }
	public HashMap<String, Tile<M>> idToTileMap() { return idToTileMap; }
	public HashMap<Tile<M>, String> tileToIdMap() { return tileToIdMap; }
	public HashSet<Integer> restarts() { return restarts; }
}

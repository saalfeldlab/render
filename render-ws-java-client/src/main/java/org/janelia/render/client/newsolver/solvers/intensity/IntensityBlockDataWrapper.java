package org.janelia.render.client.newsolver.solvers.intensity;

import mpicbg.models.Affine1D;
import mpicbg.models.Model;
import mpicbg.models.Tile;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMIntensityCorrectionParameters;

import java.util.HashMap;
import java.util.Map;


/**
 * Wrapper for all data needed to run the intensity correction solver
 */
public class IntensityBlockDataWrapper<M extends Model<M> & Affine1D<M>, F extends BlockFactory<F>> {

	final BlockData<M, ?, FIBSEMIntensityCorrectionParameters<M>, F> blockData;
	final private Map<String, Tile<M>> idToTile = new HashMap<>();

	public IntensityBlockDataWrapper(final BlockData<M, ?, FIBSEMIntensityCorrectionParameters<M>, F> blockData) {
		this.blockData = blockData;
	}

	public BlockData<M, ?, FIBSEMIntensityCorrectionParameters<M>, F>  blockData() { return blockData; }
	public Map<String, Tile<M>> idToTile() { return idToTile; }
}

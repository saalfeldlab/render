package org.janelia.render.client.newsolver.solvers;

import java.util.List;

import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;
import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;

import mpicbg.models.CoordinateTransform;

public interface Worker < M extends CoordinateTransform, P extends BlockDataSolveParameters< M >, F extends BlockFactory< F > > 
{
	/**
	 * runs the Worker
	 */
	public void run();

	/**
	 * @return - the result(s) of the solve, multiple ones if they were not connected
	 */
	public List< BlockData< M, P, F > > getBlockDataList();
}

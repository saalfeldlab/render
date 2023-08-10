package org.janelia.render.client.newsolver.solvers;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;
import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.NoninvertibleModelException;

public abstract class Worker < M extends CoordinateTransform, P extends BlockDataSolveParameters< M, P >, F extends BlockFactory< F > > 
{
	// for assigning new id's when splitting BlockData
	final protected int startId;

	final protected BlockData< M, P, F > blockData;
	final protected RenderDataClient renderDataClient;
	final protected String renderStack;

	final protected int numThreads;

	public Worker(
			final int startId,
			final BlockData< M, P, F > blockData,
			final int numThreads )
	{
		this.startId = startId;
		this.blockData = blockData;
		this.renderDataClient =
				new RenderDataClient(
						blockData.solveTypeParameters().baseDataUrl(),
						blockData.solveTypeParameters().owner(),
						blockData.solveTypeParameters().project() );
		this.renderStack = blockData.solveTypeParameters().stack();

		this.numThreads = numThreads;
	}

	/**
	 * runs the Worker
	 */
	public abstract void run() throws IOException, ExecutionException, InterruptedException, NoninvertibleModelException;

	/**
	 * @return - the result(s) of the solve, multiple ones if they were not connected
	 */
	public abstract List< BlockData< M, P, F > > getBlockDataList();
}

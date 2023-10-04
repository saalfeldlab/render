package org.janelia.render.client.newsolver.solvers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;

import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;

import mpicbg.models.NoninvertibleModelException;

/**
 * 
 * @author preibischs
 *
 * @param <R> - the result model
 * @param <P> - the solve parameters
 */
public abstract class Worker <R, P extends BlockDataSolveParameters<?, R, P>>
{
	final protected BlockData<R, P> blockData;
	final protected RenderDataClient renderDataClient;
	final protected String renderStack;

	final protected int numThreads;

	public Worker(final BlockData<R, P> blockData,
				  final int numThreads) {
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
	public abstract ArrayList<BlockData<R, P>> getBlockDataList();

	@Override
	public String toString() {
		return "worker for block " + blockData;
	}
}

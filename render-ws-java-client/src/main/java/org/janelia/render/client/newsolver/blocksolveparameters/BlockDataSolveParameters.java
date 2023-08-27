package org.janelia.render.client.newsolver.blocksolveparameters;

import java.io.Serializable;

import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;
import org.janelia.render.client.newsolver.solvers.Worker;

/**
 * 
 * @author preibischs
 *
 * @param <M> - the result model type
 */
public abstract class BlockDataSolveParameters< M, R, P extends BlockDataSolveParameters< M, R, P > > implements Serializable
{
	private static final long serialVersionUID = -813404780882760053L;

	final String baseDataUrl;
	final String owner;
	final String project;
	final String stack;

	final private M blockSolveModel;

	public BlockDataSolveParameters(
			final String baseDataUrl,
			final String owner,
			final String project,
			final String stack,
			final M blockSolveModel)
	{
		this.baseDataUrl = baseDataUrl;
		this.owner = owner;
		this.project = project;
		this.stack = stack;
		this.blockSolveModel = blockSolveModel;
	}

	public String baseDataUrl() { return baseDataUrl; }
	public String owner() { return owner; }
	public String project() { return project; }
	public String stack() { return stack; }

	public M blockSolveModel() { return blockSolveModel; }

	/**
	 * @return - the center of mass of all tiles that are part of this solve. If the coordinates are changed, the current ones should be used.
	 */
	public abstract < F extends BlockFactory< F > > double[] centerOfMass( final BlockData<M, R, P, F > blockData );

	public abstract < F extends BlockFactory< F > > Worker< M, R, P, F > createWorker(
			final BlockData< M, R, P, F > blockData,
			final int startId,
			final int threadsWorker );
}

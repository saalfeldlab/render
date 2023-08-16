package org.janelia.render.client.newsolver.blocksolveparameters;

import java.io.Serializable;

import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;
import org.janelia.render.client.newsolver.solvers.Worker;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.Model;

/**
 * 
 * @author preibischs
 *
 * @param <M> - the result model type
 */
public abstract class BlockDataSolveParameters< M extends Model< M >, R extends CoordinateTransform, B extends BlockDataSolveParameters< M, R, B > > implements Serializable
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

	public abstract < F extends BlockFactory< F > > Worker< M, R, B, F > createWorker(
			final BlockData< M, R, B, F > blockData,
			final int startId,
			final int threadsWorker );
}

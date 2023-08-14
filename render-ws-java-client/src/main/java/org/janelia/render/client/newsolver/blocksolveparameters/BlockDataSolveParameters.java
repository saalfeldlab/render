package org.janelia.render.client.newsolver.blocksolveparameters;

import java.io.Serializable;

import mpicbg.models.Model;

/**
 * 
 * @author preibischs
 *
 * @param <M> - the result model type
 */
public abstract class BlockDataSolveParameters< M extends Model< M >, B extends BlockDataSolveParameters< M, B > > implements Serializable
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

	public abstract B createInstance( boolean hasIssue );
}

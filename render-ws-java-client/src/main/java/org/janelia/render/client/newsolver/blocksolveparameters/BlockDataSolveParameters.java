package org.janelia.render.client.newsolver.blocksolveparameters;

import java.io.Serializable;

import mpicbg.models.CoordinateTransform;

/**
 * 
 * @author preibischs
 *
 * @param <M> - the result model type
 */
public class BlockDataSolveParameters< M extends CoordinateTransform > implements Serializable
{
	private static final long serialVersionUID = -813404780882760053L;

	final String baseDataUrl;
	final String owner;
	final String project;
	final String stack;

	public BlockDataSolveParameters(
			final String baseDataUrl,
			final String owner,
			final String project,
			final String stack )
	{
		this.baseDataUrl = baseDataUrl;
		this.owner = owner;
		this.project = project;
		this.stack = stack;
	}

	public String baseDataUrl() { return baseDataUrl; }
	public String owner() { return owner; }
	public String project() { return project; }
	public String stack() { return stack; }

}

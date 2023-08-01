package org.janelia.render.client.newsolver;

import java.io.Serializable;

import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;

import mpicbg.models.Model;

public abstract class BlockFactory implements Serializable
{
	private static final long serialVersionUID = 5919345114414922447L;

	public abstract <M extends Model<M>> BlockCollection< M > defineSolveSet( final BlockDataSolveParameters<M> blockSolveParameters);
}

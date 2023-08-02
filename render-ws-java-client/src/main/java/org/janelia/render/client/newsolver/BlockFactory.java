package org.janelia.render.client.newsolver;

import java.io.Serializable;

import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;

import mpicbg.models.CoordinateTransform;

public abstract class BlockFactory< F extends BlockFactory< F > > implements Serializable
{
	private static final long serialVersionUID = 5919345114414922447L;

	public abstract <M extends CoordinateTransform, P extends BlockDataSolveParameters< M >> BlockCollection< M, P, F > defineBlockCollection( final P blockSolveParameters);
}

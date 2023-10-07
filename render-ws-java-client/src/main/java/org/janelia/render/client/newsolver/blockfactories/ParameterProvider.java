package org.janelia.render.client.newsolver.blockfactories;

import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;

public interface ParameterProvider < M, R, P extends BlockDataSolveParameters< M, R, P > >
{
	P create();
}

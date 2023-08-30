package org.janelia.render.client.newsolver.blockfactories;

import java.io.Serializable;

import org.janelia.render.client.newsolver.BlockCollection;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.assembly.WeightFunction;
import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;

public interface BlockFactory< F extends BlockFactory< F > > extends Serializable
{
	public <M, R, P extends BlockDataSolveParameters< M, R, P > > BlockCollection< M, R, P, F > defineBlockCollection(
			final ParameterProvider< M, R, P > blockSolveParameterProvider );

	public WeightFunction createWeightFunction(final BlockData<?, ?, ?, F> block);
}

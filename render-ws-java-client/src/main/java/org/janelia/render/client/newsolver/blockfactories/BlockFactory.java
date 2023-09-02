package org.janelia.render.client.newsolver.blockfactories;

import java.io.Serializable;

import org.janelia.render.client.newsolver.BlockCollection;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.assembly.WeightFunction;
import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;

public interface BlockFactory extends Serializable
{
	public <M, R, P extends BlockDataSolveParameters<M, R, P>> BlockCollection<M, R, P> defineBlockCollection(
			final ParameterProvider< M, R, P > blockSolveParameterProvider );

	public WeightFunction createWeightFunction(final BlockData<?, ?, ?> block);
}

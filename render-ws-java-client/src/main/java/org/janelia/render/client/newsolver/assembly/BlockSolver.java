package org.janelia.render.client.newsolver.assembly;

import java.util.List;
import java.util.concurrent.ExecutionException;

import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;

import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;

public abstract class BlockSolver< M, R, F extends BlockFactory< F > >
{
	final private M globalModel;

	public BlockSolver( final M globalModel )
	{
		this.globalModel = globalModel;
	}

	public M globalModel() { return globalModel; }

	public abstract void globalSolve(
			List< ? extends BlockData<?, R, ?, F> > blocks,
			AssemblyMaps< M > am ) throws NotEnoughDataPointsException, IllDefinedDataPointsException, InterruptedException, ExecutionException;
}

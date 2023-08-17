package org.janelia.render.client.newsolver.assembly;

import java.util.List;
import java.util.concurrent.ExecutionException;

import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;

import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;

public abstract class BlockSolver< Z, G, R, F extends BlockFactory< F > >
{
	final private G globalModel;

	public BlockSolver( final G globalModel )
	{
		this.globalModel = globalModel;
	}

	public G globalSolveModel() { return globalModel; }

	public abstract void globalSolve(
			List< ? extends BlockData<?, R, ?, F> > blocks,
			AssemblyMaps< Z > am ) throws NotEnoughDataPointsException, IllDefinedDataPointsException, InterruptedException, ExecutionException;
}

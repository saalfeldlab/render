package org.janelia.render.client.newsolver.assembly;

import java.util.List;
import java.util.concurrent.ExecutionException;

import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;

public interface BlockSolver< R extends CoordinateTransform, F extends BlockFactory< F > >
{
	public void globalSolve(
			List< ? extends BlockData<?, R, ?, F> > blocks,
			AssemblyMaps< R > am ) throws NotEnoughDataPointsException, IllDefinedDataPointsException, InterruptedException, ExecutionException;
}

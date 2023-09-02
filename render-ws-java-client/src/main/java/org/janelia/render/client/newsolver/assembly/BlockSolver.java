package org.janelia.render.client.newsolver.assembly;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;
import org.janelia.render.client.newsolver.blockfactories.ZBlockFactory;

import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Tile;

public abstract class BlockSolver<Z, G extends Model<G>, R>
{
	final private G globalModel;

	public BlockSolver( final G globalModel )
	{
		this.globalModel = globalModel;
	}

	public G globalSolveModel() { return globalModel; }

	public abstract HashMap<BlockData<?, R, ?>, Tile<G>> globalSolve(
			List<? extends BlockData<?, R, ?>> blocks,
			AssemblyMaps<Z> am) throws NotEnoughDataPointsException, IllDefinedDataPointsException, InterruptedException, ExecutionException;
}

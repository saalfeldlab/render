package org.janelia.render.client.newsolver;

import java.util.List;

import org.janelia.render.client.newsolver.blockfactories.BlockFactory;
import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;

import mpicbg.models.CoordinateTransform;

public class BlockCollection< M extends CoordinateTransform, P extends BlockDataSolveParameters< M >, F extends BlockFactory< F > > 
{
	final List< BlockData< M, P, F > > blockDataList;
	final int minId, maxId;

	public BlockCollection( final List< BlockData< M, P, F > > blockDataList )
	{
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;

		for ( final BlockData< M, P, F > bd : blockDataList )
		{
			min = Math.min( min, bd.getId() );
			max = Math.max( max, bd.getId() );
		}

		this.blockDataList = blockDataList;
		this.minId = min;
		this.maxId = max;
	}

	public int minId() { return minId; }
	public int maxId() { return maxId; }
	public List< BlockData< M, P, F > > allBlocks() { return blockDataList; }
}

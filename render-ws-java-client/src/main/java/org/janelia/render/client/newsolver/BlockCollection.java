package org.janelia.render.client.newsolver;

import java.util.ArrayList;

import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;

public class BlockCollection<M, R, P extends BlockDataSolveParameters<M, R, P>>
{
	final ArrayList<BlockData< M, R, P>> blockDataList;
	final int minId, maxId;

	public BlockCollection( final ArrayList<BlockData<M, R, P>> blockDataList )
	{
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;

		for ( final BlockData<M, R, P> bd : blockDataList )
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
	public ArrayList< BlockData<M, R, P>> allBlocks() { return blockDataList; }
}
package org.janelia.render.client.newsolver;

import java.util.List;

public class BlockCollection
{
	final List< BlockData > blockDataList;
	final int minId, maxId;

	public BlockCollection( final List< BlockData > blockDataList )
	{
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;

		for ( final BlockData bd : blockDataList )
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
	public List< BlockData > allBlocks() { return blockDataList; }
}

package org.janelia.render.client.newsolver;

import java.util.List;

import mpicbg.models.Model;

public class BlockCollection< M extends Model< M > > 
{
	final List< BlockData< M > > blockDataList;
	final int minId, maxId;

	public BlockCollection( final List< BlockData< M > > blockDataList )
	{
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;

		for ( final BlockData< M > bd : blockDataList )
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
	public List< BlockData< M > > allBlocks() { return blockDataList; }
}

package org.janelia.render.client.newsolver;

import java.util.List;

import org.janelia.render.client.newsolver.blockfactories.BlockFactory;
import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.Model;

public class BlockCollection< M extends Model< M >, R extends CoordinateTransform, P extends BlockDataSolveParameters< M, P >, F extends BlockFactory< F > > 
{
	final List< BlockData< M, R, P, F > > blockDataList;
	final int minId, maxId;

	public BlockCollection( final List< BlockData< M, R, P, F > > blockDataList )
	{
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;

		for ( final BlockData< M, R, P, F > bd : blockDataList )
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
	public List< BlockData< M, R, P, F > > allBlocks() { return blockDataList; }
}

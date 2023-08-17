package org.janelia.render.client.newsolver;

import java.util.ArrayList;

import org.janelia.render.client.newsolver.blockfactories.BlockFactory;
import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;

import mpicbg.models.Model;

public class BlockCollection< M extends Model< M >, R, P extends BlockDataSolveParameters< M, R, P >, F extends BlockFactory< F > > 
{
	final ArrayList< BlockData< M, R, P, F > > blockDataList;
	final int minId, maxId;

	public BlockCollection( final ArrayList< BlockData< M, R, P, F > > blockDataList )
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
	public ArrayList< BlockData< M, R, P, F > > allBlocks() { return blockDataList; }
}
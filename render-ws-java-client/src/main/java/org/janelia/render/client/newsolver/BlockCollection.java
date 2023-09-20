package org.janelia.render.client.newsolver;

import java.util.ArrayList;
import java.util.IntSummaryStatistics;

import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;

public class BlockCollection<M, R, P extends BlockDataSolveParameters<M, R, P>>
{
	final ArrayList<BlockData<R, P>> blockDataList;
	final int minId, maxId;

	public BlockCollection( final ArrayList<BlockData<R, P>> blockDataList )
	{
		this.blockDataList = blockDataList;

		final IntSummaryStatistics stats = blockDataList.stream().mapToInt(BlockData::getId).summaryStatistics();
		this.minId = stats.getMin();
		this.maxId = stats.getMin();
	}

	public int minId() { return minId; }
	public int maxId() { return maxId; }
	public ArrayList<BlockData<R, P>> allBlocks() { return blockDataList; }
}
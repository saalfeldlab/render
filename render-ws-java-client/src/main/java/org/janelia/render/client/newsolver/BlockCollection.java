package org.janelia.render.client.newsolver;

import java.util.List;

import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;

public class BlockCollection<M, R, P extends BlockDataSolveParameters<M, R, P>> {
	private final List<BlockData<R, P>> blockDataList;

	public BlockCollection(final List<BlockData<R, P>> blockDataList) {
	   this.blockDataList = blockDataList;
	}

	public List<BlockData<R, P>> allBlocks() { return blockDataList; }
}
package org.janelia.render.client.newsolver;

import java.util.List;

public interface Worker
{
	/**
	 * runs the Worker
	 */
	public void run();

	/**
	 * @return - the result(s) of the solve, multiple ones if they were not connected
	 */
	public List< BlockData > getBlockDataList();
}

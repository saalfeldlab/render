package org.janelia.render.client.solver;

import java.util.List;
import java.util.Map;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.render.client.RenderDataClient;

public class RunParameters
{
	protected RenderDataClient renderDataClient;
	protected RenderDataClient matchDataClient;
	protected RenderDataClient targetDataClient;
	
	protected List<String> pGroupList;
	protected Map<String, List<Double>> sectionIdToZMap;
	protected Map<Double, ResolvedTileSpecCollection> zToTileSpecsMap;
	protected double minZ, maxZ;
	protected int totalTileCount;

	@Override
	public RunParameters clone()
	{
		final RunParameters runParams = new RunParameters();

		runParams.renderDataClient = this.renderDataClient;
		runParams.matchDataClient = this.matchDataClient;
		runParams.targetDataClient = this.targetDataClient;

		runParams.pGroupList = this.pGroupList;
		runParams.sectionIdToZMap = this.sectionIdToZMap;
		runParams.zToTileSpecsMap = this.zToTileSpecsMap;
		runParams.minZ = this.minZ;
		runParams.maxZ = this.maxZ;
		runParams.totalTileCount = totalTileCount;

		return runParams;
	}
}

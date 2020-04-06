package org.janelia.render.client.solver;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.render.client.RenderDataClient;

import net.imglib2.util.Pair;

public class RunParameters
{
	protected RenderDataClient renderDataClient;
	protected RenderDataClient matchDataClient;
	protected RenderDataClient targetDataClient;
	
	protected List< Pair< String, Double > > pGroupList;
	protected Map<String, List<Double>> sectionIdToZMap; // this is a cache
	protected Map<Double, ResolvedTileSpecCollection> zToTileSpecsMap; // this is a cache
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
		runParams.zToTileSpecsMap = new HashMap<>(); // otherwise we get synchronization issues, TODO: Reuse
		runParams.minZ = this.minZ;
		runParams.maxZ = this.maxZ;
		runParams.totalTileCount = 0;

		return runParams;
	}
}

package org.janelia.render.client.solver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.render.client.RenderDataClient;

import net.imglib2.util.Pair;

public class RunParameters
{
	public RenderDataClient renderDataClient;
	public RenderDataClient matchDataClient;
	public RenderDataClient targetDataClient;
	
	public List< Pair< String, Double > > pGroupList;
	public Map<String, ArrayList<Double>> sectionIdToZMap; // this is a cache
	public Map<Double, ResolvedTileSpecCollection> zToTileSpecsMap; // this is a cache
	public double minZ, maxZ;
	public int totalTileCount;

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

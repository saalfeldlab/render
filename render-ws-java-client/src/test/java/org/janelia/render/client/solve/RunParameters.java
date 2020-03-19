package org.janelia.render.client.solve;

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
	protected double minZ, maxZ; // min will be fixed, max will be grouped
	protected int totalTileCount;
}

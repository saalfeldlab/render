package org.janelia.render.client.newsolver.setup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackStats;
import org.janelia.alignment.util.ZFilter;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.ZRangeParameters;
import org.janelia.render.client.solver.SerializableValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.imglib2.util.Pair;

public class RenderSetup
{
	public RenderDataClient renderDataClient;
	public RenderDataClient matchDataClient;
	public RenderDataClient targetDataClient;
	
	public List< Pair< String, Double > > pGroupList;
	public Map<Integer, String> zToGroupIdMap; // a HashMap where int is the z section, and string is the description (problem, restart, ...)
	public Map<String, ArrayList<Double>> sectionIdToZMap; // this is a cache
	public Map<Double, ResolvedTileSpecCollection> zToTileSpecsMap; // this is a cache
	public Double minZ, maxZ;
	public int totalTileCount;

	@Override
	public RenderSetup clone()
	{
		final RenderSetup runParams = new RenderSetup();

		runParams.renderDataClient = this.renderDataClient;
		runParams.matchDataClient = this.matchDataClient;
		runParams.targetDataClient = this.targetDataClient;

		runParams.pGroupList = this.pGroupList;
		runParams.sectionIdToZMap = this.sectionIdToZMap;
		runParams.zToGroupIdMap = this.zToGroupIdMap;
		runParams.zToTileSpecsMap = new HashMap<>(); // otherwise we get synchronization issues, TODO: Reuse
		runParams.minZ = this.minZ;
		runParams.maxZ = this.maxZ;
		runParams.totalTileCount = 0;

		return runParams;
	}

	public static RenderSetup setupSolve(final AffineSolverSetup parameters) throws IOException {

		parameters.initDefaultValues();
		final RenderWebServiceParameters webServiceParameters = parameters.renderWeb;
		final ZRangeParameters layerRange = new ZRangeParameters();
		layerRange.minZ = parameters.minZ;
		layerRange.maxZ = parameters.maxZ;
		final String stack = parameters.stack;
		final String targetStack = parameters.targetStack;
		final String targetOwner = parameters.targetOwner;
		final String targetProject = parameters.targetProject;
		final String matchOwner = parameters.matchOwner;
		final String matchCollection = parameters.matchCollection;

		return setupSolve(webServiceParameters, targetStack, targetOwner, targetProject, matchOwner, matchCollection, stack, layerRange);
	}

	public static RenderSetup setupSolve(final IntensityCorrectionSetup parameters) throws IOException {

		parameters.initDefaultValues();
		final RenderWebServiceParameters webServiceParameters = parameters.renderWeb;
		final ZRangeParameters layerRange = parameters.intensityAdjust.layerRange;
		final String stack = parameters.intensityAdjust.stack;
		final String targetStack = parameters.targetStack;
		final String targetOwner = parameters.targetOwner;
		final String targetProject = parameters.targetProject;
		final String matchOwner = null;
		final String matchCollection = null;

		return setupSolve(webServiceParameters, targetStack, targetOwner, targetProject, matchOwner, matchCollection, stack, layerRange);
	}

	private static RenderSetup setupSolve(
			final RenderWebServiceParameters webServiceParameters,
			final String targetStack,
			final String targetOwner,
			final String targetProject,
			final String matchOwner,
			final String matchCollection,
			final String stack,
			final ZRangeParameters layerRange) throws IOException {

		final RenderSetup runParams = new RenderSetup();

		runParams.renderDataClient = webServiceParameters.getDataClient();
		runParams.matchDataClient = new RenderDataClient(webServiceParameters.baseDataUrl, matchOwner, matchCollection);
		runParams.sectionIdToZMap = new TreeMap<>();
		runParams.zToTileSpecsMap = new HashMap<>();
		runParams.totalTileCount = 0;

		if (targetStack == null) {
			runParams.targetDataClient = null;
		} else {
			runParams.targetDataClient = new RenderDataClient(webServiceParameters.baseDataUrl, targetOwner, targetProject);

			final StackMetaData sourceStackMetaData = runParams.renderDataClient.getStackMetaData(stack);
			runParams.targetDataClient.setupDerivedStack(sourceStackMetaData, targetStack);
		}

		final ZFilter zFilter = new ZFilter(layerRange.minZ, layerRange.maxZ, null);
		final List<SectionData> allSectionDataList = runParams.renderDataClient.getStackSectionData(stack, null, null );

		runParams.pGroupList = new ArrayList<>(allSectionDataList.size());

		final HashMap< String, Double > sectionIds = new HashMap<>();
		for (final SectionData data : allSectionDataList) {
			if (zFilter.accept(data.getZ())) {
				final String sectionId = data.getSectionId();
				final double z = data.getZ();

				if (!sectionIds.containsKey(sectionId))
					sectionIds.put(sectionId, z);
			}
		}

		for ( final String entry : sectionIds.keySet() )
			runParams.pGroupList.add(new SerializableValuePair<>(entry, sectionIds.get(entry)));

		runParams.pGroupList.sort(Comparator.comparing(Pair::getA));

		if (runParams.pGroupList.isEmpty())
			throw new IllegalArgumentException("stack " + stack + " does not contain any sections with the specified z values");

		Double minZForRun = layerRange.minZ;
		Double maxZForRun = layerRange.maxZ;

		// if minZ || maxZ == null in parameters, then use min and max of the stack
		if ((minZForRun == null) || (maxZForRun == null))
		{
			final StackMetaData stackMetaData = runParams.renderDataClient.getStackMetaData(stack);
			final StackStats stackStats = stackMetaData.getStats();
			if (stackStats != null)
			{
				final Bounds stackBounds = stackStats.getStackBounds();
				if (stackBounds != null)
				{
					if (minZForRun == null)
						minZForRun = stackBounds.getMinZ();

					if (maxZForRun == null)
						maxZForRun = stackBounds.getMaxZ();
				}
			}

			if ( (minZForRun == null) || (maxZForRun == null) )
				throw new IllegalArgumentException( "Failed to derive min and/or max z values for stack " + stack + ".  Stack may need to be completed.");

			layerRange.minZ = minZForRun;
			layerRange.maxZ = maxZForRun;
		}

		final Double minZ = minZForRun;
		final Double maxZ = maxZForRun;

		runParams.minZ = minZForRun;
		runParams.maxZ = maxZForRun;

		allSectionDataList.forEach(sd ->
								   {
									   final Double z = sd.getZ();
									   if ((z != null) && (z.compareTo(minZ) >= 0) && (z.compareTo(maxZ) <= 0))
									   {
										   final List<Double> zListForSection = runParams.sectionIdToZMap.computeIfAbsent(
												   sd.getSectionId(), zList -> new ArrayList<>());

										   zListForSection.add(sd.getZ());
									   }
								   });

		// a HashMap where int is the z section, and string is the description (problem, restart, ...)
		runParams.zToGroupIdMap = new HashMap<>();
		for (final String groupId : Arrays.asList("restart", "problem")) { // NOTE: "problem" groupId is for future use
			LOG.debug( "Querying: " + groupId );
			try {
				final ResolvedTileSpecCollection groupTileSpecs =
						runParams.renderDataClient.getResolvedTiles(stack,
																	runParams.minZ,
																	runParams.maxZ,
																	groupId,
																	null,
																	null,
																	null,
																	null,
																	null);
				groupTileSpecs.getTileSpecs().forEach(tileSpec -> runParams.zToGroupIdMap.put(tileSpec.getZ().intValue(), groupId));
			} catch (final IOException t) {
				LOG.info("ignoring failure to retrieve tile specs with groupId '" + groupId + "' (since it's a reasonable thing omitting the exception" );
			}
		}

		final List<Integer> challengeListZ = runParams.zToGroupIdMap.keySet().stream().sorted().collect(Collectors.toList());
		LOG.debug("setup: minZ={}, maxZ={}, challenge layers are {}", (int)Math.round(layerRange.minZ), (int)Math.round(layerRange.maxZ), challengeListZ);

		return runParams;
	}

	private static final Logger LOG = LoggerFactory.getLogger(RenderSetup.class);

}

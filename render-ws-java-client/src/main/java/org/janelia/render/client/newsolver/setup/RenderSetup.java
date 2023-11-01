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
import org.janelia.alignment.util.ZFilter;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.XYRangeParameters;
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
	public Double minX, maxX, minY, maxY, minZ, maxZ;
	public int totalTileCount;

	public static RenderSetup setupSolve(final AffineBlockSolverSetup parameters) throws IOException {

		parameters.initDefaultValues();
		final RenderWebServiceParameters webServiceParameters = parameters.renderWeb;
		final XYRangeParameters xyRange = new XYRangeParameters();
		xyRange.minX = parameters.xyRange.minX;
		xyRange.maxX = parameters.xyRange.maxX;
		xyRange.minY = parameters.xyRange.minY;
		xyRange.maxY = parameters.xyRange.maxY;
		final ZRangeParameters layerRange = new ZRangeParameters();
		layerRange.minZ = parameters.zRange.minZ;
		layerRange.maxZ = parameters.zRange.maxZ;
		final String stack = parameters.stack;
		final String targetStack = parameters.targetStack.stack;
		final String targetOwner = parameters.targetStack.owner;
		final String targetProject = parameters.targetStack.project;
		final String matchOwner = parameters.matches.matchOwner;
		final String matchCollection = parameters.matches.matchCollection;

		return setupSolve(webServiceParameters, targetStack, targetOwner, targetProject, matchOwner, matchCollection, stack, xyRange, layerRange);
	}

	public static RenderSetup setupSolve(final IntensityCorrectionSetup parameters) throws IOException {

		parameters.initDefaultValues();
		final RenderWebServiceParameters webServiceParameters = parameters.renderWeb;
		final ZRangeParameters layerRange = parameters.layerRange;
		final XYRangeParameters xyRange = new XYRangeParameters();
		final String stack = parameters.intensityAdjust.stack;
		final String targetStack = parameters.targetStack.stack;
		final String targetOwner = parameters.targetStack.owner;
		final String targetProject = parameters.targetStack.project;

		return setupSolve(webServiceParameters, targetStack, targetOwner, targetProject, null, null, stack, xyRange, layerRange);
	}

	private static RenderSetup setupSolve(
			final RenderWebServiceParameters webServiceParameters,
			final String targetStack,
			final String targetOwner,
			final String targetProject,
			final String matchOwner,
			final String matchCollection,
			final String stack,
			final XYRangeParameters xyRange,
			final ZRangeParameters layerRange) throws IOException {

		final RenderSetup runParams = new RenderSetup();

		runParams.renderDataClient = webServiceParameters.getDataClient();
		runParams.matchDataClient = new RenderDataClient(webServiceParameters.baseDataUrl, matchOwner, matchCollection);
		runParams.sectionIdToZMap = new TreeMap<>();
		runParams.zToTileSpecsMap = new HashMap<>();
		runParams.totalTileCount = 0;

		final StackMetaData sourceStackMetaData = runParams.renderDataClient.getStackMetaData(stack);
		if (targetStack == null) {
			runParams.targetDataClient = null;
		} else {
			runParams.targetDataClient = new RenderDataClient(webServiceParameters.baseDataUrl, targetOwner, targetProject);
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
		if (runParams.pGroupList.isEmpty()) {
			throw new IllegalArgumentException("stack " + stack + " does not contain any sections with the specified z values");
		}

		// setup bounds for run using stack bounds and user specified bounds ...
		final Bounds stackBounds = sourceStackMetaData.getStackBounds();
		runParams.minX = xyRange.minX == null ? stackBounds.getMinX() : Math.max(xyRange.minX, stackBounds.getMinX());
		runParams.maxX = xyRange.maxX == null ? stackBounds.getMaxX() : Math.min(xyRange.maxX, stackBounds.getMaxX());
		runParams.minY = xyRange.minY == null ? stackBounds.getMinY() : Math.max(xyRange.minY, stackBounds.getMinY());
		runParams.maxY = xyRange.maxY == null ? stackBounds.getMaxY() : Math.min(xyRange.maxY, stackBounds.getMaxY());
		runParams.minZ = layerRange.minZ == null ? stackBounds.getMinZ() : Math.max(layerRange.minZ, stackBounds.getMinZ());
		runParams.maxZ = layerRange.maxZ == null ? stackBounds.getMaxZ() : Math.min(layerRange.maxZ, stackBounds.getMaxZ());

		allSectionDataList.forEach(sectionData -> {
			final Double z = sectionData.getZ();
			if ((z != null) && (z.compareTo(runParams.minZ) >= 0) && (z.compareTo(runParams.maxZ) <= 0)) {
				final List<Double> zListForSection = runParams.sectionIdToZMap.computeIfAbsent(
						sectionData.getSectionId(), zList -> new ArrayList<>());

				zListForSection.add(sectionData.getZ());
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
				LOG.info("ignoring failure to retrieve tile specs with groupId '" + groupId + "' (since it's a reasonable thing omitting the exception)");
			}
		}

		final List<Integer> challengeListZ = runParams.zToGroupIdMap.keySet().stream().sorted().collect(Collectors.toList());

		LOG.debug("setup: minZ={}, maxZ={}, challenge layers are {}",
				  runParams.minZ.intValue(), runParams.maxZ.intValue(), challengeListZ);

		return runParams;
	}

	private static final Logger LOG = LoggerFactory.getLogger(RenderSetup.class);

}

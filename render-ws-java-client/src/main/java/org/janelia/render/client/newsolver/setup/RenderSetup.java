package org.janelia.render.client.newsolver.setup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
import org.janelia.render.client.solver.RunParameters;
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

	public static RenderSetup setupSolve( final AffineSolverSetup parameters ) throws IOException
	{
		final RenderSetup runParams = new RenderSetup();

		parameters.initDefaultValues();

		if ( parameters.blockSize < 3 )
			throw new RuntimeException( "Blocksize has to be >= 3." );

		runParams.renderDataClient = parameters.renderWeb.getDataClient();
		runParams.matchDataClient = new RenderDataClient(
				parameters.renderWeb.baseDataUrl,
				parameters.matchOwner,
				parameters.matchCollection);

		runParams.sectionIdToZMap = new TreeMap<>();
		runParams.zToTileSpecsMap = new HashMap<>();
		runParams.totalTileCount = 0;

		if (parameters.targetStack == null)
		{
			runParams.targetDataClient = null;
		}
		else
		{
			runParams.targetDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl, parameters.targetOwner, parameters.targetProject);

			final StackMetaData sourceStackMetaData = runParams.renderDataClient.getStackMetaData(parameters.stack);
			runParams.targetDataClient.setupDerivedStack(sourceStackMetaData, parameters.targetStack);
		}

		final ZFilter zFilter = new ZFilter(parameters.minZ,parameters.maxZ,null);
		final List<SectionData> allSectionDataList = runParams.renderDataClient.getStackSectionData(parameters.stack, null, null );

		runParams.pGroupList = new ArrayList<>(allSectionDataList.size());

		/*
		runParams.pGroupList.addAll(
				allSectionDataList.stream()
						.filter(sectionData -> zFilter.accept(sectionData.getZ()))
						.map(SectionData::getSectionId)
						.distinct()
						.sorted()
						.collect(Collectors.toList()));
		*/

		final HashMap< String, Double > sectionIds = new HashMap<>();
		for ( final SectionData data : allSectionDataList )
		{
			if ( zFilter.accept( data.getZ() ) )
			{
				final String sectionId = data.getSectionId();
				final double z = data.getZ().doubleValue();

				if ( !sectionIds.containsKey( sectionId ) )
					sectionIds.put( sectionId, z );
			}
		}

		for ( final String entry : sectionIds.keySet() )
			runParams.pGroupList.add( new SerializableValuePair< String, Double >( entry, sectionIds.get( entry ) ) );

		Collections.sort( runParams.pGroupList, new Comparator< Pair< String, Double > >()
		{
			@Override
			public int compare( final Pair< String, Double > o1, final Pair< String, Double > o2 )
			{
				return o1.getA().compareTo( o2.getA() );
			}
		} );

		if (runParams.pGroupList.size() == 0)
			throw new IllegalArgumentException("stack " + parameters.stack + " does not contain any sections with the specified z values");

		Double minZForRun = parameters.minZ;
		Double maxZForRun = parameters.maxZ;

		// if minZ || maxZ == null in parameters, then use min and max of the stack
		if ((minZForRun == null) || (maxZForRun == null))
		{
			final StackMetaData stackMetaData = runParams.renderDataClient.getStackMetaData(parameters.stack);
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
				throw new IllegalArgumentException( "Failed to derive min and/or max z values for stack " + parameters.stack + ".  Stack may need to be completed.");

			parameters.minZ = minZForRun;
			parameters.maxZ = maxZForRun;
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
						runParams.renderDataClient.getResolvedTiles(parameters.stack,
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
		LOG.debug("setup: minZ={}, maxZ={}, challenge layers are {}", (int)Math.round(parameters.minZ), (int)Math.round(parameters.maxZ), challengeListZ);

		return runParams;
	}

	private static final Logger LOG = LoggerFactory.getLogger(RenderSetup.class);

}

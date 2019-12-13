package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.SortedConnectedCanvasIdClusters;
import org.janelia.alignment.match.TileIdsWithMatches;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.TileClusterParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for identifying connected tile clusters in a stack.
 *
 * @author Eric Trautman
 */
public class ClusterCountClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Stack name",
                required = true)
        public String stack;

        @ParametersDelegate
        TileClusterParameters tileCluster = new TileClusterParameters();

        @Parameter(
                names = "--minZ",
                description = "Minimum Z value for layers to be processed")
        public Double minZ;

        @Parameter(
                names = "--maxZ",
                description = "Maximum Z value for layers to be processed")
        public Double maxZ;

        @Parameter(
                names = "--maxLayersPerBatch",
                description = "Maximum number of adjacent layers to connect at one time.  " +
                              "Larger connected stacks will use too much memory and/or recurse too deeply.  " +
                              "This option (in conjunction with --maxOverlapZ) allows processing to be divided " +
                              "into smaller batches.")
        public Integer maxLayersPerBatch = 1000;

        @Parameter(
                names = "--maxOverlapLayers",
                description = "Maximum number of adjacent layers for matched tiles " +
                              "(ensures connections can be tracked across batches for large runs)")
        public Integer maxOverlapLayers = 10;
    }

    /**
     * @param  args  see {@link Parameters} for command line argument details.
     */
    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                parameters.tileCluster.validate(true);

                LOG.info("runClient: entry, parameters={}", parameters);

                final ClusterCountClient client = new ClusterCountClient(parameters);
                client.findConnectedClusters();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    private ClusterCountClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    private void findConnectedClusters()
            throws Exception {

        final RenderDataClient renderDataClient = parameters.renderWeb.getDataClient();

        final RenderDataClient matchDataClient =
                parameters.tileCluster.getMatchDataClient(parameters.renderWeb.baseDataUrl,
                                                          parameters.renderWeb.owner);

        final Map<Double, Set<String>> zToSectionIdsMap =
                renderDataClient.getStackZToSectionIdsMap(parameters.stack,
                                                          parameters.minZ,
                                                          parameters.maxZ,
                                                          null);

        //TODO: For very large stacks (e.g. millions of tiles) we will not be able to keep all canvasIds
        //      (one per tile) in memory.
        //      We minimally need the canvasIds for the overlap area and just a count of the other tiles.
        //      Deferring this problem to later since current use case involves smaller (< 100,000 tile) stacks.

        final List<Double> sortedZValues = zToSectionIdsMap.keySet().stream().sorted().collect(Collectors.toList());
        final SortedConnectedCanvasIdClusters allClusters = new SortedConnectedCanvasIdClusters(new ArrayList<>());
        final Set<String> allUnconnectedTileIds = new HashSet<>();

        final int layerCount = sortedZValues.size();
        for (int i = 0; i < layerCount; i += parameters.maxLayersPerBatch) {
            final int fromIndex = i > parameters.maxOverlapLayers ? i - parameters.maxOverlapLayers : i;
            final int toIndex = Math.min(i + parameters.maxLayersPerBatch, layerCount);
            final SortedConnectedCanvasIdClusters clusters =
                    findConnectedClustersForSlab(renderDataClient,
                                                 matchDataClient,
                                                 zToSectionIdsMap,
                                                 sortedZValues.subList(fromIndex, toIndex),
                                                 allUnconnectedTileIds);
            allClusters.mergeOverlappingClusters(clusters);
        }

        LOG.info("findConnectedClusters: found {} connected tile sets with sizes {}",
                 allClusters.size(),
                 allClusters.getClusterSizes());

        for (final Set<String> tileIdSet : allClusters.getSortedConnectedTileIdSets()) {

            final int tileCount = tileIdSet.size();
            final String tileIdInfo;
            if ((tileCount > 1) && (tileCount < 1_000_000)) {
                final List<String> sortedTileIds = tileIdSet.stream().sorted().collect(Collectors.toList());
                tileIdInfo = "with first tile " + sortedTileIds.get(0) +
                             " and last tile " + sortedTileIds.get(tileCount - 1);
            } else {
                tileIdInfo = "including tile " + tileIdSet.stream().findAny().orElse(null);
            }

            LOG.info("findConnectedClusters: {} tile set {}", tileCount, tileIdInfo);

        }

    }

    @Nonnull
    private SortedConnectedCanvasIdClusters findConnectedClustersForSlab(final RenderDataClient renderDataClient,
                                                                         final RenderDataClient matchDataClient,
                                                                         final Map<Double, Set<String>> zToSectionIdsMap,
                                                                         final List<Double> sortedZValues,
                                                                         final Set<String> allUnconnectedTileIds)
            throws IOException {

        final Set<String> allStackTileIds = new HashSet<>();
        final List<CanvasMatches> allCanvasMatches = new ArrayList<>();

        for (final Double z : sortedZValues) {

            final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(parameters.stack, z);
            allStackTileIds.addAll(resolvedTiles.getTileIds());

            for (final String sectionId : zToSectionIdsMap.get(z)) {
                final List<CanvasMatches> matchedPairs;
                if (parameters.tileCluster.includeMatchesOutsideGroup) {
                    matchedPairs = matchDataClient.getMatchesWithPGroupId(sectionId,true);
                } else {
                    matchedPairs = matchDataClient.getMatchesWithinGroup(sectionId,true);
                }
                allCanvasMatches.addAll(matchedPairs);
            }

        }

        final TileIdsWithMatches allTileIdsWithMatches = new TileIdsWithMatches();
        allTileIdsWithMatches.addMatches(allCanvasMatches, allStackTileIds);

        allStackTileIds.forEach(tileId -> {
            if (! allTileIdsWithMatches.contains(tileId)) {
                allUnconnectedTileIds.add(tileId);
            }
        });

        final SortedConnectedCanvasIdClusters clusters =
                new SortedConnectedCanvasIdClusters(allTileIdsWithMatches.getCanvasMatchesList());

        LOG.info("findConnectedClustersForSlab: slab z {} to {} has {} connected tile sets with sizes {}",
                 sortedZValues.get(0),
                 sortedZValues.get(sortedZValues.size() - 1),
                 clusters.size(),
                 clusters.getClusterSizes());

        return clusters;
    }

    private static final Logger LOG = LoggerFactory.getLogger(ClusterCountClient.class);
}

package org.janelia.render.client;

import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.ConnectedTileClusterSummary;
import org.janelia.alignment.match.ConnectedTileClusterSummaryForStack;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.match.SortedConnectedCanvasIdClusters;
import org.janelia.alignment.match.TileIdsWithMatches;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.match.UnconnectedTileEdges;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
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
        public MultiProjectParameters multiProject = new MultiProjectParameters();

        @ParametersDelegate
        public TileClusterParameters tileCluster = new TileClusterParameters();

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

                LOG.info("runClient: entry, parameters={}", parameters);

                final ClusterCountClient client = new ClusterCountClient(parameters);
                client.findConnectedClusters();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    public ClusterCountClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    public void findConnectedClusters()
            throws Exception {

        final RenderDataClient renderDataClient = parameters.multiProject.getDataClient();
        final List<StackWithZValues> stackWithZList = parameters.multiProject.buildListOfStackWithAllZ();
        for (final StackWithZValues stackWithZ : stackWithZList) {

            final StackId stackId = stackWithZ.getStackId();
            final MatchCollectionId defaultMatchCollectionId =
                    parameters.multiProject.getMatchCollectionIdForStack(stackId);

            findConnectedClustersForStack(stackWithZ,
                                          defaultMatchCollectionId,
                                          renderDataClient,
                                          parameters.tileCluster);
        }

    }

    public ConnectedTileClusterSummaryForStack findConnectedClustersForStack(final StackWithZValues stackWithZValues,
                                                                             final MatchCollectionId matchCollectionId,
                                                                             final RenderDataClient renderDataClient,
                                                                             final TileClusterParameters tileClusterParameters)
            throws Exception {


        final StackId stackId = stackWithZValues.getStackId();
        final RenderDataClient matchDataClient = renderDataClient.buildClient(matchCollectionId.getOwner(),
                                                                              matchCollectionId.getName());

        final Map<Double, Set<String>> zToSectionIdsMap =
                renderDataClient.getStackZToSectionIdsMap(stackId.getStack(),
                                                          null,
                                                          null,
                                                          stackWithZValues.getzValues());

        //TODO: For very large stacks (e.g. millions of tiles) we will not be able to keep all canvasIds
        //      (one per tile) in memory.
        //      We minimally need the canvasIds for the overlap area and just a count of the other tiles.
        //      Deferring this problem to later since current use case involves smaller (< 100,000 tile) stacks.

        final List<Double> sortedZValues = zToSectionIdsMap.keySet().stream().sorted().collect(Collectors.toList());
        final SortedConnectedCanvasIdClusters allClusters = new SortedConnectedCanvasIdClusters(new ArrayList<>());
        final Set<String> allUnconnectedTileIds = new HashSet<>();
        final UnconnectedTileEdges unconnectedEdges =
                parameters.tileCluster.maxLayersForUnconnectedEdge < 1 ? null :
                new UnconnectedTileEdges(parameters.tileCluster.maxLayersForUnconnectedEdge);

        final int layerCount = sortedZValues.size();
        for (int i = 0; i < layerCount; i += tileClusterParameters.maxLayersPerBatch) {
            final int fromIndex = i > tileClusterParameters.maxOverlapLayers ? i - tileClusterParameters.maxOverlapLayers : i;
            final int toIndex = Math.min(i + tileClusterParameters.maxLayersPerBatch, layerCount);
            final SortedConnectedCanvasIdClusters clusters =
                    findConnectedClustersForSlab(renderDataClient,
                                                 stackId.getStack(),
                                                 matchDataClient,
                                                 zToSectionIdsMap,
                                                 sortedZValues.subList(fromIndex, toIndex),
                                                 allUnconnectedTileIds,
                                                 unconnectedEdges);
            allClusters.mergeOverlappingClusters(clusters);
        }

        LOG.info("findConnectedClusters: found {} connected tile set{} with size{} {}",
                 allClusters.size(),
                 allClusters.size() == 1 ? "" : "s",
                 allClusters.size() == 1 ? "" : "s",
                 allClusters.getClusterSizes());

        final ConnectedTileClusterSummaryForStack stackClusterSummary =
                new ConnectedTileClusterSummaryForStack(stackId);

        stackClusterSummary.setUnconnectedTileIdList(allUnconnectedTileIds);

        for (final Set<String> tileIdSet : allClusters.getSortedConnectedTileIdSets()) {

            final int tileCount = tileIdSet.size();
            final ConnectedTileClusterSummary tileSummary;
            if ((tileCount > 1) && (tileCount < 1_000_000)) {
                final List<String> sortedTileIds = tileIdSet.stream().sorted().collect(Collectors.toList());
                tileSummary = new ConnectedTileClusterSummary(tileCount,
                                                              sortedTileIds.get(0),
                                                              sortedTileIds.get(tileCount - 1));
            } else {
                tileSummary = new ConnectedTileClusterSummary(tileCount,
                                                              tileIdSet.stream().findAny().orElse(null));
            }
            stackClusterSummary.addTileClusterSummary(tileSummary);
        }

        if (unconnectedEdges != null) {
            final List<String> unconnectedTileEdgesList = unconnectedEdges.buildSortedUnconnectedEdgeList();
            final boolean hasTooManyConsecutiveUnconnectedEdges = unconnectedEdges.hasTooManyConsecutiveUnconnectedEdges();
            stackClusterSummary.setUnconnectedEdgeData(unconnectedTileEdgesList,
                                                       hasTooManyConsecutiveUnconnectedEdges);
        }

        LOG.info("findConnectedClusters: {} ", stackClusterSummary.toDetailsString());

        return stackClusterSummary;
    }

    private SortedConnectedCanvasIdClusters findConnectedClustersForSlab(final RenderDataClient renderDataClient,
                                                                         final String stack,
                                                                         final RenderDataClient matchDataClient,
                                                                         final Map<Double, Set<String>> zToSectionIdsMap,
                                                                         final List<Double> sortedZValues,
                                                                         final Set<String> allUnconnectedTileIds,
                                                                         final UnconnectedTileEdges unconnectedTileEdges)
            throws IOException {

        final Set<String> allStackTileIds = new HashSet<>();
        final List<CanvasMatches> allCanvasMatches = new ArrayList<>();

        for (final Double z : sortedZValues) {

            final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(stack, z);
            allStackTileIds.addAll(resolvedTiles.getTileIds());

            for (final String sectionId : zToSectionIdsMap.get(z)) {
                final List<CanvasMatches> matchedPairs;
                if (parameters.tileCluster.includeMatchesOutsideGroup) {
                    matchedPairs = matchDataClient.getMatchesWithPGroupId(sectionId,true);
                } else {
                    matchedPairs = matchDataClient.getMatchesWithinGroup(sectionId,true);
                }

                if (unconnectedTileEdges != null) {
                    unconnectedTileEdges.appendUnconnectedEdgesForZ(z, resolvedTiles, matchedPairs);
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

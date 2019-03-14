package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.janelia.alignment.match.SortedConnectedCanvasIdClusters;
import org.janelia.alignment.match.TileIdsWithMatches;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.TileClusterParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for removing (or reporting) tiles that are not connected (via point matches)
 * to any other tiles in the same layer.
 *
 * @author Eric Trautman
 */
public class UnconnectedTileRemovalClient {

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
                names = "--reportRemovedTiles",
                description = "Log unconnected tile IDs instead of removing them from the specified stack",
                arity = 0)
        public boolean reportRemovedTiles = false;

        @Parameter(
                names = "--saveRemovedTiles",
                description = "Place removed tiles in <stack>_removed_tiles stack for review",
                arity = 0)
        public boolean saveRemovedTiles = false;

        @Parameter(
                names = "--removedTilesStackName",
                description = "Use this name for the removed tiles stack instead of the default <stack>_removed_tiles")
        public String removedTilesStackName;

        @Parameter(
                names = "--keeperOwner",
                description = "Owner for the keeper stack (default is same as owner)")
        public String keeperOwner;

        @Parameter(
                names = "--keeperProject",
                description = "Project for the keeper stack (default is same as project)")
        public String keeperProject;

        @Parameter(
                names = "--keeperStack",
                description = "Keep tiles that exist in this stack regardless of their cluster size")
        public String keeperStack;


        @Parameter(
                names = "--completeStacksAfterRemoval",
                description = "Complete source and/or removed tiles stacks after processing",
                arity = 0)
        public boolean completeStacksAfterRemoval = false;

        @Parameter(
                names = "--z",
                description = "Explicit z values for sections to be processed",
                required = true,
                variableArity = true) // e.g. --z 20.0 21.0 22.0
        public List<Double> zValues;

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

                final UnconnectedTileRemovalClient client = new UnconnectedTileRemovalClient(parameters);
                client.removeTiles();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    private UnconnectedTileRemovalClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    private void removeTiles()
            throws Exception {

        final RenderDataClient renderDataClient = parameters.renderWeb.getDataClient();
        final RenderDataClient matchDataClient =
                parameters.tileCluster.getMatchDataClient(parameters.renderWeb.baseDataUrl,
                                                          parameters.renderWeb.owner);

        final String removedTilesStackName = parameters.removedTilesStackName == null ?
                                             parameters.stack + "_removed_tiles" :
                                             parameters.removedTilesStackName;


        final RenderDataClient keeperClient;
        if (parameters.keeperStack != null) {
            final String o = parameters.keeperOwner == null ? parameters.renderWeb.owner : parameters.keeperOwner;
            final String p = parameters.keeperProject == null ? parameters.renderWeb.project : parameters.keeperProject;
            keeperClient = new RenderDataClient(parameters.renderWeb.baseDataUrl, o, p);
        } else {
            keeperClient = null;
        }

        int totalUnconnectedTiles = 0;

        for (final Double z : parameters.zValues) {

            final TileIdsWithMatches tileIdsWithMatches = getTileIdsWithMatches(renderDataClient,
                                                                                parameters.stack,
                                                                                z,
                                                                                matchDataClient);
            final Set<String> keeperTileIds = new HashSet<>();
            if (keeperClient != null) {
                keeperClient.getTileBounds(parameters.keeperStack, z).forEach(tb -> keeperTileIds.add(tb.getTileId()));
            }

            final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(parameters.stack, z);
            final Set<String> unconnectedTileIds = new HashSet<>();
            for (final TileSpec tileSpec : resolvedTiles.getTileSpecs()) {
                final String tileId = tileSpec.getTileId();
                if ((! tileIdsWithMatches.contains(tileId)) && (! keeperTileIds.contains(tileId))) {
                    unconnectedTileIds.add(tileId);
               }
            }

            if (parameters.tileCluster.isDefined()) {

                final SortedConnectedCanvasIdClusters clusters =
                        new SortedConnectedCanvasIdClusters(tileIdsWithMatches.getCanvasMatchesList());
                final List<Set<String>> sortedConnectedTileSets = clusters.getSortedConnectedTileIdSets();

                LOG.info("removeTiles: for z {}, found {} connected tile sets with sizes {}",
                         z, clusters.size(), clusters.getClusterSizes());

                markSmallClustersAsUnconnected(parameters.tileCluster,
                                               z,
                                               sortedConnectedTileSets,
                                               keeperTileIds,
                                               unconnectedTileIds);
            }

            if (unconnectedTileIds.size() > 0) {

                LOG.info("removeTiles: found {} unconnected tiles for z {}", unconnectedTileIds.size(), z);

                if (parameters.saveRemovedTiles) {

                    if (totalUnconnectedTiles == 0) {
                        final StackMetaData sourceStackMetaData = renderDataClient.getStackMetaData(parameters.stack);
                        renderDataClient.setupDerivedStack(sourceStackMetaData, removedTilesStackName);
                    }

                    final ResolvedTileSpecCollection removedTiles = getFilteredCollection(resolvedTiles,
                                                                                          unconnectedTileIds);

                    if (removedTiles.getTileCount() > 0) {

                        // NOTE: don't delete existing tiles from removed stack in case this is a second pass
                        renderDataClient.saveResolvedTiles(removedTiles, removedTilesStackName, z);

                    } else {
                        LOG.warn("removeTiles: skipping save of unconnected tiles for z {} since they have already been removed",
                                 z);
                    }

                }

                if (parameters.reportRemovedTiles) {

                    final TreeSet<String> sortedTileIds = new TreeSet<>(unconnectedTileIds);
                    LOG.info("for z {}, the following tiles are not connected: {}", z, sortedTileIds);

                } else {

                    if (totalUnconnectedTiles == 0) {
                        renderDataClient.ensureStackIsInLoadingState(parameters.stack, null);
                    }

                    resolvedTiles.removeTileSpecs(unconnectedTileIds);

                    if (resolvedTiles.getTileCount() > 0) {

                        renderDataClient.deleteStack(parameters.stack, z);
                        renderDataClient.saveResolvedTiles(resolvedTiles, parameters.stack, z);

                    } else {
                        LOG.warn("removeTiles: skipping removal of unconnected tiles for z {} since they have already been removed",
                                 z);
                    }

                }

                totalUnconnectedTiles += unconnectedTileIds.size();

            } else {

                LOG.info("all tiles with z {} are connected", z);

            }

        }

        if (parameters.completeStacksAfterRemoval && (totalUnconnectedTiles > 0)) {

            if (! parameters.reportRemovedTiles) {
                renderDataClient.setStackState(parameters.stack, StackMetaData.StackState.COMPLETE);
            }

            if (parameters.saveRemovedTiles) {
                renderDataClient.setStackState(removedTilesStackName, StackMetaData.StackState.COMPLETE);
            }

        }

        LOG.info("found {} unconnected tiles across all layers", totalUnconnectedTiles);
    }

    static TileIdsWithMatches getTileIdsWithMatches(final RenderDataClient stackClient,
                                                    final String stackName,
                                                    final Double z,
                                                    final RenderDataClient matchClient)
            throws IOException {

        final List<SectionData> sectionDataList = stackClient.getStackSectionData(stackName, z, z);
        final TileIdsWithMatches tileIdsWithMatches = new TileIdsWithMatches();
        for (final SectionData sectionData : sectionDataList) {
            tileIdsWithMatches.addMatches(matchClient.getMatchesWithinGroup(sectionData.getSectionId()));
        }

        return tileIdsWithMatches;
    }

    static List<Set<String>> markSmallClustersAsUnconnected(final TileClusterParameters clusterParameters,
                                                            final Double z,
                                                            final List<Set<String>> sortedConnectedTileSets,
                                                            final Set<String> keeperTileIds,
                                                            final Set<String> unconnectedTileIds) {

        final List<Set<String>> smallerRemainingClusters = new ArrayList<>();

        if (sortedConnectedTileSets.size() > 1) {

            // keep largest connected tile set regardless of size
            final Set<String> largestCluster = sortedConnectedTileSets.get(0);
            final int maxSmallClusterSize = clusterParameters.getEffectiveMaxSmallClusterSize(largestCluster.size());

            LOG.info("markSmallClustersAsUnconnected: for z {}, maxSmallClusterSize is {}",
                     z, maxSmallClusterSize);

            final List<Integer> remainingClusterSizes = new ArrayList<>();
            remainingClusterSizes.add(largestCluster.size());

            for (int i = 1; i < sortedConnectedTileSets.size(); i++) {
                final Set<String> clusterTileIds = sortedConnectedTileSets.get(i);
                if (clusterTileIds.size() <= maxSmallClusterSize) {

                    String keeperTileId = null;
                    if (keeperTileIds.size() > 0) {
                        for (final String tileId : clusterTileIds) {
                            if (keeperTileIds.contains(tileId)) {
                                keeperTileId = tileId;
                                break;
                            }
                        }
                    }

                    if (keeperTileId == null) {
                        unconnectedTileIds.addAll(clusterTileIds);
                        LOG.info("markSmallClustersAsUnconnected: removed small {} tile cluster: {}",
                                 clusterTileIds.size(), getSortedSet(clusterTileIds));
                    } else {
                        smallerRemainingClusters.add(clusterTileIds);
                        remainingClusterSizes.add(clusterTileIds.size());
                        LOG.info("markSmallClustersAsUnconnected: keeping small {} tile cluster with tile {}: {}",
                                 clusterTileIds.size(), keeperTileId, getSortedSet(clusterTileIds));
                    }

                } else {
                    smallerRemainingClusters.add(clusterTileIds);
                    remainingClusterSizes.add(clusterTileIds.size());
                }
            }

            LOG.info("markSmallClustersAsUnconnected: for z {}, {} clusters remain with sizes {}",
                     z, remainingClusterSizes.size(), remainingClusterSizes);

        }

        return smallerRemainingClusters;
    }

    static ResolvedTileSpecCollection getFilteredCollection(final ResolvedTileSpecCollection allTiles,
                                                            final Set<String> keepTileIds) {
        final ResolvedTileSpecCollection filteredTiles =
                new ResolvedTileSpecCollection(allTiles.getTransformSpecs(),
                                               allTiles.getTileSpecs());
        filteredTiles.removeDifferentTileSpecs(keepTileIds);
        return filteredTiles;
    }

    private static List<String> getSortedSet(final Set<String> set) {
        return set.stream().sorted().collect(Collectors.toList());
    }

    private static final Logger LOG = LoggerFactory.getLogger(UnconnectedTileRemovalClient.class);
}

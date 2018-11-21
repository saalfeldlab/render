package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.janelia.alignment.match.CanvasMatches;
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

                parameters.tileCluster.validate();

                LOG.info("runClient: entry, parameters={}", parameters);

                final UnconnectedTileRemovalClient client = new UnconnectedTileRemovalClient(parameters);
                client.removeTiles();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    UnconnectedTileRemovalClient(final Parameters parameters) {
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

        int totalUnconnectedTiles = 0;

        for (final Double z : parameters.zValues) {

            final List<SectionData> sectionDataList = renderDataClient.getStackSectionData(parameters.stack, z, z);
            final Set<String> tileIdsWithMatches = new HashSet<>();
            final List<CanvasMatches> matchesList = new ArrayList<>();
            for (final SectionData sectionData : sectionDataList) {
                for (final CanvasMatches matches : matchDataClient.getMatchesWithinGroup(sectionData.getSectionId())) {
                    matchesList.add(matches);
                    tileIdsWithMatches.add(matches.getpId());
                    tileIdsWithMatches.add(matches.getqId());
                }
            }

            final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(parameters.stack, z);
            final Set<String> unconnectedTileIds = new HashSet<>();
            for (final TileSpec tileSpec : resolvedTiles.getTileSpecs()) {
                final String tileId = tileSpec.getTileId();
                if (! tileIdsWithMatches.contains(tileId)) {
                    unconnectedTileIds.add(tileId);
               }
            }

            if (parameters.tileCluster.isDefined()) {
                markSmallClustersAsUnconnected(z, matchesList, unconnectedTileIds);
            }

            if (unconnectedTileIds.size() > 0) {

                LOG.info("removeTiles: found {} unconnected tiles for z {}", unconnectedTileIds.size(), z);

                if (parameters.saveRemovedTiles) {

                    if (totalUnconnectedTiles == 0) {
                        final StackMetaData sourceStackMetaData = renderDataClient.getStackMetaData(parameters.stack);
                        renderDataClient.setupDerivedStack(sourceStackMetaData, removedTilesStackName);
                    }

                    final ResolvedTileSpecCollection removedTiles =
                            new ResolvedTileSpecCollection(resolvedTiles.getTransformSpecs(),
                                                           resolvedTiles.getTileSpecs());
                    removedTiles.removeDifferentTileSpecs(unconnectedTileIds);

                    if (removedTiles.getTileCount() > 0) {

                        removedTiles.removeUnreferencedTransforms();
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

                        resolvedTiles.removeUnreferencedTransforms();
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

    void markSmallClustersAsUnconnected(final Double z,
                                        final List<CanvasMatches> matchesList,
                                        final Set<String> unconnectedTileIds) {

        final List<Set<String>> connectedTileSets = TileClusterParameters.buildAndSortConnectedTileSets(z, matchesList);

        if (connectedTileSets.size() > 1) {

            // keep largest connected tile set regardless of size
            final Set<String> largestCluster = connectedTileSets.remove(connectedTileSets.size() - 1);
            final int maxSmallClusterSize = parameters.tileCluster.getEffectiveMaxSmallClusterSize(largestCluster.size());

            LOG.info("markSmallClustersAsUnconnected: for z {}, maxSmallClusterSize is {}",
                     z, maxSmallClusterSize);

            final List<Integer> remainingClusterSizes = new ArrayList<>();
            for (final Set<String> clusterTileIds : connectedTileSets) {
                if (clusterTileIds.size() <= maxSmallClusterSize) {
                    unconnectedTileIds.addAll(clusterTileIds);
                    LOG.info("markSmallClustersAsUnconnected: removed small {} tile cluster: {}",
                             clusterTileIds.size(), clusterTileIds.stream().sorted().collect(Collectors.toList()));
                } else {
                    remainingClusterSizes.add(clusterTileIds.size());
                }
            }

            remainingClusterSizes.add(largestCluster.size());

            LOG.info("markSmallClustersAsUnconnected: for z {}, {} clusters remain with sizes {}",
                     z, remainingClusterSizes.size(), remainingClusterSizes);

        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(UnconnectedTileRemovalClient.class);
}

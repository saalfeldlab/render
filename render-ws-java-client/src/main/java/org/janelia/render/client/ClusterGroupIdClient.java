package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.janelia.alignment.match.SortedConnectedCanvasIdClusters;
import org.janelia.alignment.match.TileIdsWithMatches;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.TileClusterParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for assigning tile spec groupIds based upon connected tile clusters.
 *
 * @author Eric Trautman
 */
public class ClusterGroupIdClient {

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
                names = "--completeStack",
                description = "Complete target stack after processing",
                arity = 0)
        public boolean completeStack = false;

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

                final ClusterGroupIdClient client = new ClusterGroupIdClient(parameters);
                client.setGroupIds();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    private ClusterGroupIdClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    private void setGroupIds()
            throws Exception {

        final RenderDataClient renderDataClient = parameters.renderWeb.getDataClient();

        final RenderDataClient matchDataClient =
                parameters.tileCluster.getMatchDataClient(parameters.renderWeb.baseDataUrl,
                                                          parameters.renderWeb.owner);

        boolean firstUpdate = true;
        for (final Double z : parameters.zValues) {

            final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(parameters.stack, z);
            final Set<String> stackTileIds = new HashSet<>(resolvedTiles.getTileIds());

            final TileIdsWithMatches tileIdsWithMatches =
                    UnconnectedTileRemovalClient.getTileIdsWithMatches(renderDataClient,
                                                                       parameters.stack,
                                                                       z,
                                                                       matchDataClient,
                                                                       stackTileIds,
                                                                       parameters.tileCluster.includeMatchesOutsideGroup);

            final Set<String> unconnectedTileIds = new HashSet<>();
            stackTileIds.forEach(tileId -> {
                if (! tileIdsWithMatches.contains(tileId)) {
                    unconnectedTileIds.add(tileId);
                }
            });

            final SortedConnectedCanvasIdClusters clusters =
                    new SortedConnectedCanvasIdClusters(tileIdsWithMatches.getCanvasMatchesList());
            final List<Set<String>> sortedConnectedTileIdSets = clusters.getSortedConnectedTileIdSets();

            LOG.info("setGroupIds: for z {}, found {} connected tile sets with sizes {}",
                     z, clusters.size(), clusters.getClusterSizes());

            int updatedTileCount = 0;
            for (int clusterIndex = 0; clusterIndex < sortedConnectedTileIdSets.size(); clusterIndex++ ) {
                final String clusterGroupId = String.format("cluster_%06d", clusterIndex);
                updatedTileCount += setGroupIdForCluster(resolvedTiles,
                                                         sortedConnectedTileIdSets.get(clusterIndex),
                                                         clusterGroupId);
            }

            final String unconnectedGroupId = "unconnected";
            updatedTileCount += setGroupIdForCluster(resolvedTiles, unconnectedTileIds, unconnectedGroupId);

            LOG.info("setGroupIds: found {} unconnected tiles for z {}", unconnectedTileIds.size(), z);

            if (updatedTileCount > 0) {

                if (firstUpdate) {
                    renderDataClient.ensureStackIsInLoadingState(parameters.stack, null);
                    firstUpdate = false;
                }

                renderDataClient.saveResolvedTiles(resolvedTiles, parameters.stack, z);
            }

            LOG.info("setGroupIds: updated groupIds for {} tiles with z {}", updatedTileCount, z);

        }

        if (parameters.completeStack) {
            renderDataClient.setStackState(parameters.stack, StackMetaData.StackState.COMPLETE);
        }

    }

    private int setGroupIdForCluster(final ResolvedTileSpecCollection allTiles,
                                     final Set<String> cluster,
                                     final String clusterGroupId) {

        int updatedTileCount = 0;
        for (final String tileId : cluster) {
            final TileSpec tileSpec = allTiles.getTileSpec(tileId);
            if (tileSpec != null) {
                tileSpec.setGroupId(clusterGroupId);
                updatedTileCount++;
            }
        }

        if (updatedTileCount > 0) {
            LOG.info("set groupId to {} for cluster with {} tiles ({} of those tiles were found in the source stack)",
                     clusterGroupId, cluster.size(), updatedTileCount);
        }

        return updatedTileCount;
    }

    private static final Logger LOG = LoggerFactory.getLogger(ClusterGroupIdClient.class);
}

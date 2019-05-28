package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.janelia.alignment.ArgbRenderer;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.match.ClusterOverlapProblem;
import org.janelia.alignment.match.SortedConnectedCanvasIdClusters;
import org.janelia.alignment.match.TileIdsWithMatches;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.FileUtil;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.alignment.util.RenderWebServiceUrls;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.TileClusterParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for splitting a stack's layers (zs) into separate layers for each connected cluster of tiles.
 *
 * @author Eric Trautman
 */
public class SplitClusterClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Stack name",
                required = true)
        public String stack;

        @Parameter(
                names = "--targetOwner",
                description = "Name of target stack owner (default is same as source stack owner)"
        )
        public String targetOwner;

        @Parameter(
                names = "--targetProject",
                description = "Name of target stack project (default is same as source stack project)"
        )
        public String targetProject;

        @Parameter(
                names = "--targetStack",
                description = "Name of target stack for split clusters (if unspecified, split clusters will not be saved)")
        public String targetStack;

        @ParametersDelegate
        TileClusterParameters tileCluster = new TileClusterParameters();

        @Parameter(
                names = "--maxClustersPerOriginalZ",
                description = "Maximum number of clusters for each layer " +
                              "(for CATMAID it is best if this evenly divides into the source stack zResolution e.g. 40 for FAFB)",
                required = true)
        public Integer maxClustersPerOriginalZ;

        @Parameter(
                names = "--saveRemovedTiles",
                description = "Place removed tiles in <stack>_removed_tiles stack for review",
                arity = 0)
        public boolean saveRemovedTiles = false;

        @Parameter(
                names = "--rootOutputDirectory",
                description = "Root directory for cluster intersection images")
        public String rootOutputDirectory = ".";

        @Parameter(
                names = "--renderIntersectingClusters",
                description = "Render any cluster intersection areas for review",
                arity = 0)
        public boolean renderIntersectingClusters = false;

        @Parameter(
                names = "--renderClustersMaxCount",
                description = "If specified, clusters with this number of tiles or fewer will be rendered for review")
        public Integer renderClustersMaxCount;

        @Parameter(
                names = "--completeStacks",
                description = "Complete target and/or removed tiles stacks after processing",
                arity = 0)
        public boolean completeStacks = false;

        @Parameter(
                names = "--z",
                description = "Explicit z values for sections to be processed",
                required = true,
                variableArity = true) // e.g. --z 20.0 21.0 22.0
        public List<Double> zValues;

        public String getTargetOwner() {
            return targetOwner == null ? renderWeb.owner : targetOwner;
        }

        public String getTargetProject() {
            return targetProject == null ? renderWeb.project : targetProject;
        }
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

                final SplitClusterClient client = new SplitClusterClient(parameters);
                client.splitLayers();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    private SplitClusterClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    private void splitLayers()
            throws Exception {

        final RenderDataClient renderDataClient = parameters.renderWeb.getDataClient();
        final RenderWebServiceUrls renderWebServiceUrls = renderDataClient.getUrls();

        final RenderDataClient matchDataClient =
                parameters.tileCluster.getMatchDataClient(parameters.renderWeb.baseDataUrl,
                                                          parameters.renderWeb.owner);
        final RenderDataClient targetClient =
                new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                     parameters.getTargetOwner(),
                                     parameters.getTargetProject());

        final String removedTilesStackName = parameters.stack + "_removed_tiles";

        final StackMetaData sourceStackMetaData = renderDataClient.getStackMetaData(parameters.stack);
        final List<Double> resolutionValues = sourceStackMetaData.getCurrentResolutionValues();
        if (resolutionValues.size() == 3) {
            final Double zResolution = resolutionValues.remove(2);
            resolutionValues.add(zResolution / parameters.maxClustersPerOriginalZ);
            sourceStackMetaData.setCurrentResolutionValues(resolutionValues);
        }

        if (parameters.targetStack != null) {
            targetClient.setupDerivedStack(sourceStackMetaData, parameters.targetStack);
        }

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

            LOG.info("splitLayers: for z {}, found {} connected tile sets with sizes {}",
                     z, clusters.size(), clusters.getClusterSizes());

            final List<Set<String>> smallerRemainingClusters =
                    UnconnectedTileRemovalClient.markSmallClustersAsUnconnected(parameters.tileCluster,
                                                                                z,
                                                                                sortedConnectedTileIdSets,
                                                                                new HashSet<>(),
                                                                                unconnectedTileIds);
            setZForClusters(resolvedTiles,
                            z,
                            sortedConnectedTileIdSets.get(0),
                            smallerRemainingClusters);

            LOG.info("splitLayers: found {} unconnected tiles for z {}", unconnectedTileIds.size(), z);

            if (parameters.saveRemovedTiles) {

                final ResolvedTileSpecCollection removedTiles =
                        UnconnectedTileRemovalClient.getFilteredCollection(resolvedTiles,
                                                                           unconnectedTileIds);

                if (removedTiles.getTileCount() > 0) {
                    targetClient.saveResolvedTiles(removedTiles, removedTilesStackName, z);
                }

            }

            resolvedTiles.removeTileSpecs(unconnectedTileIds);

            if (parameters.renderIntersectingClusters) {

                final List<List<TileBounds>> clusterBoundsLists = getClusterBoundsLists(resolvedTiles,
                                                                                        sortedConnectedTileIdSets);

                final List<ClusterOverlapProblem> overlapProblems =
                        ClusterOverlapProblem.findOverlapProblems(z, clusterBoundsLists);

                if (overlapProblems.size() > 0) {
                    final File imageDir = FileUtil.createBatchedZDirectory(parameters.rootOutputDirectory,
                                                                           "problem_overlap_batch_",
                                                                           z);
                    overlapProblems.forEach(op -> op.render(renderWebServiceUrls, parameters.stack, imageDir));
                    saveProblemJson(overlapProblems, imageDir, z);
                    overlapProblems.forEach(ClusterOverlapProblem::logProblemDetails);
                } else {
                    LOG.info("no overlap problems found");
                }

            }

            if (parameters.renderClustersMaxCount != null) {
                renderSmallClusters(z,
                                    resolvedTiles,
                                    sortedConnectedTileIdSets,
                                    renderWebServiceUrls);
            }

            if ((parameters.targetStack != null) && (resolvedTiles.getTileCount() > 0)) {
                targetClient.saveResolvedTiles(resolvedTiles, parameters.targetStack, null);

                if (parameters.targetStack.equals(parameters.stack)) {
                    // if source and target are same stack, remove tiles from source layer (z)
                    targetClient.deleteStack(parameters.targetStack, z);
                }
            }

        }

        if (parameters.completeStacks) {

            if (parameters.targetStack != null) {
                targetClient.setStackState(parameters.targetStack, StackMetaData.StackState.COMPLETE);
            }

            if (parameters.saveRemovedTiles) {
                targetClient.setStackState(removedTilesStackName, StackMetaData.StackState.COMPLETE);
            }

        }

    }

    /**
     * @return list of tile bounds lists for each cluster with at least one tile in the resolved tiles set.
     */
    private List<List<TileBounds>> getClusterBoundsLists(final ResolvedTileSpecCollection resolvedTiles,
                                                         final List<Set<String>> sortedConnectedTileIdSets) {
        final List<List<TileBounds>> clusterBoundsLists = new ArrayList<>();

        for (final Set<String> clusterTileIds : sortedConnectedTileIdSets) {
            final List<TileBounds> clusterBoundsList = new ArrayList<>(clusterTileIds.size());
            for (final String tileId : clusterTileIds) {
                final TileSpec tileSpec = resolvedTiles.getTileSpec(tileId);
                if (tileSpec != null) {
                    clusterBoundsList.add(tileSpec.toTileBounds());
                }
            }
            if (clusterBoundsList.size() > 0) {
                clusterBoundsLists.add(clusterBoundsList);
            }
        }
        return clusterBoundsLists;
    }

    private void saveProblemJson(final List<ClusterOverlapProblem> overlapProblems,
                                 final File imageDir,
                                 final Double z)
            throws IOException {

        final String jsonFileName = String.format("problem_overlap_tile_ids_%s_z%06.0f.json", parameters.stack, z);
        final Path path = Paths.get(imageDir.getAbsolutePath(), jsonFileName);

        final StringBuilder json = new StringBuilder();
        json.append("[\n");
        for (final ClusterOverlapProblem problem : overlapProblems) {
            if (json.length() > 2) {
                json.append(",\n");
            }
            json.append(problem.toJson());
        }
        json.append("\n]");

        Files.write(path, json.toString().getBytes(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
    }

    private int setZForCluster(final ResolvedTileSpecCollection allTiles,
                               final Double originalZ,
                               final Set<String> cluster,
                               final int clusterIndex) {

        final Double z = (originalZ * parameters.maxClustersPerOriginalZ) + clusterIndex;
        int updatedTileCount = 0;
        for (final String tileId : cluster) {
            final TileSpec tileSpec = allTiles.getTileSpec(tileId);
            if (tileSpec != null) {
                tileSpec.setZ(z);
                tileSpec.setGroupId(originalZ.toString());
                updatedTileCount++;
            }
        }

        if (updatedTileCount > 0) {
            LOG.info("set z to {} for cluster with {} tiles ({} of those tiles were found in the source stack)",
                     z, cluster.size(), updatedTileCount);
        }

        return updatedTileCount;
    }

    private void setZForClusters(final ResolvedTileSpecCollection allTiles,
                                 final Double originalZ,
                                 final Set<String> largestCluster,
                                 final List<Set<String>> smallerRemainingClusters) {

        setZForCluster(allTiles, originalZ, largestCluster, 0);

        final List<Integer> missingClusterSizes = new ArrayList<>();
        int clusterIndex = 1;
        for (final Set<String> remainingCluster : smallerRemainingClusters) {

            final int updatedTileCount = setZForCluster(allTiles, originalZ, remainingCluster, clusterIndex);

            if (updatedTileCount > 0) {

                if (clusterIndex > parameters.maxClustersPerOriginalZ) {
                    throw new IllegalArgumentException(
                            "z " + originalZ + " has more than --maxClustersPerOriginalZ " +
                            parameters.maxClustersPerOriginalZ + " clusters");
                }

                clusterIndex++;

            } else {
                missingClusterSizes.add(remainingCluster.size());
            }
        }

        if (missingClusterSizes.size() > 0) {
            LOG.info("source stack is completely missing clusters with sizes {}", missingClusterSizes);
        }
    }

    private void renderSmallClusters(final Double originalZ,
                                     final ResolvedTileSpecCollection resolvedTiles,
                                     final List<Set<String>> sortedConnectedTileIdSets,
                                     final RenderWebServiceUrls renderWebServiceUrls) {

        final List<List<TileBounds>> clusterBoundsLists = getClusterBoundsLists(resolvedTiles,
                                                                                sortedConnectedTileIdSets);
        final StringBuilder renderedClusterJson = new StringBuilder();

        int clusterNumber = 0;
        File imageDir = null;
        for (final List<TileBounds> clusterBoundsList : clusterBoundsLists) {

            if ((clusterBoundsList.size() > 0) && (clusterBoundsList.size() <= parameters.renderClustersMaxCount)) {

                final List<Bounds> boundsList = new ArrayList<>(clusterBoundsList);
                @SuppressWarnings("OptionalGetWithoutIsPresent")
                final Bounds clusterBounds = boundsList.stream().reduce(Bounds::union).get();

                final RenderParameters renderParameters =
                        ClusterOverlapProblem.getScaledRenderParametersForBounds(renderWebServiceUrls,
                                                                                 parameters.stack,
                                                                                 originalZ,
                                                                                 clusterBounds,
                                                                                 1200);

                // remove nearby tiles that are not part of cluster
                final Set<String> tileIdsToKeep = clusterBoundsList.stream()
                        .map(TileBounds::getTileId)
                        .collect(Collectors.toSet());
                renderParameters.removeTileSpecsOutsideSet(tileIdsToKeep);

                final BufferedImage targetImage = renderParameters.openTargetImage();
                ArgbRenderer.render(renderParameters, targetImage, ImageProcessorCache.DISABLED_CACHE);
                ClusterOverlapProblem.drawClusterBounds(targetImage, renderParameters, clusterBoundsList, Color.GREEN);

                if (imageDir == null) {
                    imageDir = FileUtil.createBatchedZDirectory(parameters.rootOutputDirectory,
                                                                "z_cluster_batch_",
                                                                originalZ);

                }

                final String fileName = String.format("z%06.0f_cluster_%03d.jpg", originalZ, clusterNumber);
                final File imageFile = new File(imageDir, fileName).getAbsoluteFile();

                try {
                    Utils.saveImage(targetImage, imageFile, false, 0.85f);
                } catch (final IOException e) {
                    LOG.error("failed to save " + imageFile, e);
                }

                if (renderedClusterJson.length() == 0) {
                    renderedClusterJson.append("[\n  ");
                } else {
                    renderedClusterJson.append(",\n  ");
                }
                renderedClusterJson.append(getClusterJson(clusterBoundsList, clusterNumber));

            }

            clusterNumber++;
        }

        if (imageDir != null) {
            renderedClusterJson.append("\n]");
            final String fileName = String.format("z%06.0f_clusters.json", originalZ);
            final Path path = Paths.get(imageDir.getAbsolutePath(), fileName);
            try {
                Files.write(path, renderedClusterJson.toString().getBytes());
            } catch (final IOException e) {
                LOG.error("failed to save " + path, e);
            }
        }

    }

    private String getClusterJson(final List<TileBounds> clusterBoundsList,
                                  final int clusterNumber) {
        final List<String> tileIds = clusterBoundsList.stream().map(TileBounds::getTileId).collect(Collectors.toList());
        return "{\n  \"clusterNumber\": " + clusterNumber + ",\n  \"tileIds\": " +
               ClusterOverlapProblem.getTileIdListJson(tileIds) + "\n}";
    }

    private static final Logger LOG = LoggerFactory.getLogger(SplitClusterClient.class);
}

package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.janelia.alignment.ArgbRenderer;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.SortedConnectedCanvasIdClusters;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileBoundsRTree;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackStats;
import org.janelia.alignment.util.ImageProcessorCache;
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
                names = "--separateSmallerUnconnectedClusters",
                description = "If specified and more than one cluster exists for a section after removal, " +
                              "leave the largest cluster in that section and then separate any smaller clusters " +
                              "into independent layers by assigning them sequential integral z values " +
                              "after the stack's highest z value",
                arity = 0)
        public boolean separateSmallerUnconnectedClusters = false;

        @Parameter(
                names = "--renderIntersectingClusters",
                description = "Render any cluster intersection areas for review",
                arity = 0)
        public boolean renderIntersectingClusters = false;

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

                if ((parameters.tileCluster == null) || (parameters.tileCluster.matchCollection == null)) {
                    throw new IllegalArgumentException("--matchCollection must be specified");
                }

                parameters.tileCluster.validate();

                LOG.info("runClient: entry, parameters={}", parameters);

                final UnconnectedTileRemovalClient client = new UnconnectedTileRemovalClient(parameters);
                client.removeTiles();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private Double smallClusterZ = null;

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


        if (parameters.separateSmallerUnconnectedClusters) {
            final StackMetaData stackMetaData = renderDataClient.getStackMetaData(parameters.stack);
            Bounds stackBounds = null;
            final StackStats stats = stackMetaData.getStats();
            if (stats != null) {
                stackBounds = stats.getStackBounds();
            }
            if (stackBounds == null) {
                throw new IllegalStateException("Bounds are not defined for stack '" + parameters.stack +
                                                "'. The stack likely needs to be COMPLETED." );
            }
            smallClusterZ = stackBounds.getMaxZ() + 1;
        }

        int totalUnconnectedTiles = 0;
        int totalNumberOfSeparatedClusters = 0;

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

            boolean foundSmallerClustersToSeparate = false;
            if (parameters.tileCluster.isDefined()) {

                final SortedConnectedCanvasIdClusters clusters = new SortedConnectedCanvasIdClusters(matchesList);
                final List<Set<String>> sortedConnectedTileSets = clusters.getSortedConnectedTileIdSets();

                LOG.info("removeTiles: for z {}, found {} connected tile sets with sizes {}",
                         z, clusters.size(), clusters.getClusterSizes());

                final int largestSetIndex = sortedConnectedTileSets.size() - 1;
                final int firstRemainingSetIndex =
                        markSmallClustersAsUnconnected(z, sortedConnectedTileSets, unconnectedTileIds);

                foundSmallerClustersToSeparate = (parameters.separateSmallerUnconnectedClusters) &&
                                                 (firstRemainingSetIndex < largestSetIndex);

                if (foundSmallerClustersToSeparate) {

                    renderDataClient.ensureStackIsInLoadingState(parameters.stack, null);

                    final List<Set<String>> smallerRemainingTileSets =
                            sortedConnectedTileSets.subList(firstRemainingSetIndex, largestSetIndex);

                    final double firstSeparatedZ = smallClusterZ;

                    separateSmallerRemainingClusters(resolvedTiles,
                                                     smallerRemainingTileSets,
                                                     renderDataClient);

                    final int numberOfSeparatedClusters = (int) (smallClusterZ - firstSeparatedZ);
                    totalNumberOfSeparatedClusters += numberOfSeparatedClusters;
                }

            }

            if ((unconnectedTileIds.size() > 0) || foundSmallerClustersToSeparate) {

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

        if (parameters.completeStacksAfterRemoval &&
            ((totalUnconnectedTiles > 0) || (totalNumberOfSeparatedClusters > 0))) {

            if (! parameters.reportRemovedTiles) {
                renderDataClient.setStackState(parameters.stack, StackMetaData.StackState.COMPLETE);
                if ((totalNumberOfSeparatedClusters > 0) && (parameters.zValues.size() == 1)) {
                    findIntersectingSeparatedClusters(renderDataClient);
                }
            }

            if (parameters.saveRemovedTiles) {
                renderDataClient.setStackState(removedTilesStackName, StackMetaData.StackState.COMPLETE);
            }

        }

        LOG.info("separated {} small clusters into new layers", totalNumberOfSeparatedClusters);
        LOG.info("found {} unconnected tiles across all layers", totalUnconnectedTiles);
    }

    int markSmallClustersAsUnconnected(final Double z,
                                       final List<Set<String>> sortedConnectedTileSets,
                                       final Set<String> unconnectedTileIds) {

        int firstRemainingClusterIndex = 0;

        if (sortedConnectedTileSets.size() > 1) {

            // keep largest connected tile set regardless of size
            final int largestSetIndex = sortedConnectedTileSets.size() - 1;
            final Set<String> largestCluster = sortedConnectedTileSets.get(largestSetIndex);
            final int maxSmallClusterSize = parameters.tileCluster.getEffectiveMaxSmallClusterSize(largestCluster.size());

            LOG.info("markSmallClustersAsUnconnected: for z {}, maxSmallClusterSize is {}",
                     z, maxSmallClusterSize);

            final List<Integer> remainingClusterSizes = new ArrayList<>();
            for (int i = 0; i < largestSetIndex; i++) {
                final Set<String> clusterTileIds = sortedConnectedTileSets.get(i);
                if (clusterTileIds.size() <= maxSmallClusterSize) {
                    unconnectedTileIds.addAll(clusterTileIds);
                    LOG.info("markSmallClustersAsUnconnected: removed small {} tile cluster: {}",
                             clusterTileIds.size(), clusterTileIds.stream().sorted().collect(Collectors.toList()));
                } else {
                    remainingClusterSizes.add(clusterTileIds.size());
                }
            }

            remainingClusterSizes.add(largestCluster.size());
            firstRemainingClusterIndex = sortedConnectedTileSets.size() - remainingClusterSizes.size();

            LOG.info("markSmallClustersAsUnconnected: for z {}, firstRemainingClusterIndex is {}, {} clusters remain with sizes {}",
                     z, firstRemainingClusterIndex, remainingClusterSizes.size(), remainingClusterSizes);

        }

        return firstRemainingClusterIndex;
    }

    private void separateSmallerRemainingClusters(final ResolvedTileSpecCollection allTiles,
                                                  final List<Set<String>> smallerRemainingTileSets,
                                                  final RenderDataClient renderDataClient)
            throws IOException {

        LOG.info("separateSmallerRemainingClusters: separating {} clusters",
                 smallerRemainingTileSets.size());

        for (final Set<String> remainingCluster : smallerRemainingTileSets) {

            final ResolvedTileSpecCollection filteredTiles = getFilteredCollection(allTiles, remainingCluster);

            if (filteredTiles.hasTileSpecs()) {

                allTiles.removeTileSpecs(remainingCluster);

                for (final TileSpec tileSpec : filteredTiles.getTileSpecs()) {
                    tileSpec.setZ(smallClusterZ);
                }

                renderDataClient.saveResolvedTiles(filteredTiles, parameters.stack, smallClusterZ);

                LOG.info("separateSmallerRemainingClusters: changed z to {} for {} tile cluster: {}",
                         smallClusterZ,
                         remainingCluster.size(),
                         remainingCluster.stream().sorted().collect(Collectors.toList()));

                smallClusterZ += 1;

            } else {

                // NOTE: This happens when tiles in the cluster have matches but the tiles
                //       were already dropped by another process (e.g. montage solve).

                LOG.info("separateSmallerRemainingClusters: skip missing {} tile cluster: {}",
                         remainingCluster.size(),
                         remainingCluster.stream().sorted().collect(Collectors.toList()));

            }
        }

    }

    private List<Double> getZList(final RenderDataClient renderDataClient)
            throws IOException {

        final List<SectionData> stackSectionDataList = renderDataClient.getStackSectionData(parameters.stack,
                                                                                            null,
                                                                                            null);
        return stackSectionDataList.stream()
                .map(SectionData::getZ)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    private void findIntersectingSeparatedClusters(final RenderDataClient renderDataClient)
            throws IOException {

        final List<Double> zList = getZList(renderDataClient);

        final Map<Double, Map<String, TileBounds>> zToBoundsMap = new HashMap<>();
        final List<TileBoundsRTree> boundsRTrees = new ArrayList<>();
        for (final Double z : zList) {
            final List<TileBounds> tileBoundsList = renderDataClient.getTileBounds(parameters.stack, z);
            final Map<String, TileBounds> boundsMap = new HashMap<>();
            tileBoundsList.forEach(tileBounds -> boundsMap.put(tileBounds.getTileId(), tileBounds));
            zToBoundsMap.put(z, boundsMap);
            boundsRTrees.add(new TileBoundsRTree(z, tileBoundsList));
        }

        final List<OverlapProblem> overlapProblems = new ArrayList<>();
        OverlapProblem overlapProblem;
        for (int i = 0; i < zList.size(); i++) {

            final Double z = zList.get(i);
            final TileBoundsRTree boundsRTree = boundsRTrees.get(i);

            for (int j = i + 1; j < zList.size(); j++) {

                final Double otherZ = zList.get(j);
                overlapProblem = null;

                for (final TileBounds tileBounds : zToBoundsMap.get(otherZ).values()) {

                    final List<TileBounds> intersectingTiles = boundsRTree.findTilesInBox(tileBounds.getMinX(),
                                                                                          tileBounds.getMinY(),
                                                                                          tileBounds.getMaxX(),
                                                                                          tileBounds.getMaxY());
                    if (intersectingTiles.size() > 0) {
                        if (overlapProblem == null) {
                            overlapProblem = new OverlapProblem(otherZ,
                                                                tileBounds,
                                                                z,
                                                                intersectingTiles);
                        } else {
                            overlapProblem.addProblem(tileBounds, intersectingTiles);
                        }
                    }

                }

                if (overlapProblem != null) {
                    overlapProblems.add(overlapProblem);
                }
            }

        }

        if (overlapProblems.size() > 0) {
            if (parameters.renderIntersectingClusters) {
                overlapProblems.forEach(op -> op.render(renderDataClient, parameters.stack));
            }
            overlapProblems.forEach(OverlapProblem::logProblemDetails);
        } else {
            LOG.info("no overlap problems found");
        }
    }

    private class OverlapProblem {

        private final Double z;
        private final List<TileBounds> tileBoundsList;
        private final Double intersectingZ;
        private final List<TileBounds> intersectingTileBoundsList;
        private final List<String> problemDetailsList;
        private File problemImageFile;

        OverlapProblem(final Double z,
                       final TileBounds tileBounds,
                       final Double intersectingZ,
                       final List<TileBounds> intersectingTileBoundsList) {
            this.z = z;
            this.tileBoundsList = new ArrayList<>();
            this.intersectingZ = intersectingZ;
            this.intersectingTileBoundsList = new ArrayList<>();
            this.problemDetailsList = new ArrayList<>();
            this.problemImageFile = null;
            addProblem(tileBounds, intersectingTileBoundsList);
        }

        void addProblem(final TileBounds tileBounds,
                        final List<TileBounds> intersectingTileBoundsList)  {

            this.tileBoundsList.add(tileBounds);
            this.intersectingTileBoundsList.addAll(intersectingTileBoundsList);

            final List<String> intersectingTileIds = intersectingTileBoundsList.stream()
                    .map(TileBounds::getTileId)
                    .collect(Collectors.toList());
            final String details = "cluster overlap: z " + z + " tile " + tileBounds.getTileId() +
                                   " overlaps z " + intersectingZ + " tile(s) " + intersectingTileIds;
            this.problemDetailsList.add(details);
        }

        void logProblemDetails() {
            problemDetailsList.forEach(LOG::warn);
            if (problemImageFile != null) {
                LOG.warn("cluster overlap image saved to {}\n", problemImageFile);
            }
        }

        RenderParameters getParameters(final RenderDataClient renderDataClient,
                                       final Bounds intersectingBounds,
                                       final Double z) {
            final String urlString =
                    renderDataClient.getRenderParametersUrlString(parameters.stack,
                                                                  intersectingBounds.getMinX(),
                                                                  intersectingBounds.getMinY(),
                                                                  z,
                                                                  (int) intersectingBounds.getDeltaX(),
                                                                  (int) intersectingBounds.getDeltaY(),
                                                                  0.05,
                                                                  null);
            return RenderParameters.loadFromUrl(urlString);
        }

        void drawTileBounds(final Graphics2D targetGraphics,
                            final RenderParameters renderParameters,
                            final TileBounds tileBounds,
                            final Color color) {
            targetGraphics.setStroke(new BasicStroke(2));
            targetGraphics.setColor(color);
            final int x = (int) ((tileBounds.getMinX() - renderParameters.getX()) * renderParameters.getScale());
            final int y = (int) ((tileBounds.getMinY() - renderParameters.getY()) * renderParameters.getScale());
            final int width = (int) (tileBounds.getDeltaX() * renderParameters.getScale());
            final int height = (int) (tileBounds.getDeltaY() * renderParameters.getScale());
            targetGraphics.drawRect(x, y, width, height);
        }

        void render(final RenderDataClient renderDataClient,
                    final String stackName) {

            final List<Bounds> allBounds = new ArrayList<>(intersectingTileBoundsList);
            allBounds.addAll(tileBoundsList);

            @SuppressWarnings("OptionalGetWithoutIsPresent")
            final Bounds problemBounds = allBounds.stream().reduce(Bounds::union).get();

            final RenderParameters parameters = getParameters(renderDataClient, problemBounds, z);
            final RenderParameters otherParameters = getParameters(renderDataClient, problemBounds, intersectingZ);
            for (final TileSpec tileSpec : otherParameters.getTileSpecs()) {
                parameters.addTileSpec(tileSpec);
            }

            final BufferedImage targetImage = parameters.openTargetImage();
            ArgbRenderer.render(parameters, targetImage, ImageProcessorCache.DISABLED_CACHE);

            final Graphics2D targetGraphics = targetImage.createGraphics();
            intersectingTileBoundsList.forEach(tb -> drawTileBounds(targetGraphics, parameters, tb, Color.GREEN));
            tileBoundsList.forEach(tb -> drawTileBounds(targetGraphics, parameters, tb, Color.RED));
            targetGraphics.dispose();

            final String problemName = String.format("problem_overlap_%s_gz%1.0f_rz%1.0f_x%d_y%d.jpg",
                                                     stackName, intersectingZ, z,
                                                     (int) parameters.getX(), (int) parameters.getY());
            this.problemImageFile = new File(problemName).getAbsoluteFile();

            try {
                Utils.saveImage(targetImage, this.problemImageFile, false, 0.85f);
            } catch (final IOException e) {
                LOG.error("failed to save " + problemName, e);
            }
        }

    }

    private ResolvedTileSpecCollection getFilteredCollection(final ResolvedTileSpecCollection allTiles,
                                                             final Set<String> keepTileIds) {
        final ResolvedTileSpecCollection filteredTiles =
                new ResolvedTileSpecCollection(allTiles.getTransformSpecs(),
                                               allTiles.getTileSpecs());
        filteredTiles.removeDifferentTileSpecs(keepTileIds);
        return filteredTiles;
    }



    private static final Logger LOG = LoggerFactory.getLogger(UnconnectedTileRemovalClient.class);
}

package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import mpicbg.trakem2.transform.TranslationModel2D;
import mpicbg.trakem2.transform.CoordinateTransform;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.SortedConnectedCanvasIdClusters;
import org.janelia.alignment.match.TileIdsWithMatches;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileBoundsRTree;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.validator.TileSpecValidator;
import org.janelia.alignment.warp.AbstractWarpTransformBuilder;
import org.janelia.alignment.warp.RigidBuilder;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.TileClusterParameters;
import org.janelia.render.client.parameter.TileSpecValidatorParameters;
import org.janelia.render.client.parameter.WarpStackParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.janelia.alignment.spec.ResolvedTileSpecCollection.TransformApplicationMethod.PRE_CONCATENATE_LAST;

/**
 * Java client for transforming one stack (a "montage" stack) based upon that
 * stack's tile's center points in another stack (an "align" stack).
 * This client is very similar to the {@link WarpTransformClient} but it uses a {@link mpicbg.models.RigidModel2D}
 * model for the transformation which will rotate and translate the montage stack but will not warp it.
 *
 * TODO: refactor this and the WarpTransformClient(s) into one generic client
 *
 * transform independently,
 * terrace missing clusters and overlaps > threshold into separate stack,
 * for overlaps < threshold, render
 * for overlaps > threshold
 *
 * @author Eric Trautman
 */
public class RigidTransformClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @ParametersDelegate
        public TileSpecValidatorParameters tileSpecValidator = new TileSpecValidatorParameters();

        @ParametersDelegate
        WarpStackParameters warp = new WarpStackParameters();

        @ParametersDelegate
        TileClusterParameters tileCluster = new TileClusterParameters();

        @Parameter(
                names = "--transformClustersIndependently",
                description = "Transform clusters independently",
                arity = 0)
        public boolean transformClustersIndependently = false;

        @Parameter(
                description = "Z values",
                required = true)
        public List<String> zValues;
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);
                parameters.warp.initDefaultValues(parameters.renderWeb);

                LOG.info("runClient: entry, parameters={}", parameters);

                final RigidTransformClient client = new RigidTransformClient(parameters);

                client.setUpDerivedStack();

                for (final String z : parameters.zValues) {
                    client.generateStackDataForZ(new Double(z));
                }

                if (parameters.warp.completeTargetStack) {
                    client.completeTargetStack();
                }
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final TileSpecValidator tileSpecValidator;

    private final RenderDataClient montageDataClient;
    private final RenderDataClient alignDataClient;
    private final RenderDataClient targetDataClient;
    private final RenderDataClient matchDataClient;

    private RigidTransformClient(final Parameters parameters) {
        this.parameters = parameters;
        this.tileSpecValidator = parameters.tileSpecValidator.getValidatorInstance();
        this.montageDataClient = parameters.renderWeb.getDataClient();
        this.alignDataClient = parameters.warp.getAlignDataClient();
        this.targetDataClient = parameters.warp.getTargetDataClient();
        if (parameters.tileCluster.isDefined()) {
            this.matchDataClient = parameters.tileCluster.getMatchDataClient(parameters.renderWeb.baseDataUrl,
                                                                             parameters.renderWeb.owner);
        } else {
            this.matchDataClient = null;
        }
    }

    private void setUpDerivedStack() throws Exception {
        final StackMetaData montageStackMetaData = montageDataClient.getStackMetaData(parameters.warp.montageStack);
        targetDataClient.setupDerivedStack(montageStackMetaData, parameters.warp.targetStack);
    }

    private void completeTargetStack() throws Exception {
        targetDataClient.setStackState(parameters.warp.targetStack, StackMetaData.StackState.COMPLETE);
    }

    private void generateStackDataForZ(final Double z)
            throws Exception {

        LOG.info("generateStackDataForZ: entry, z={}", z);

        final List<SectionData> sectionDataList = montageDataClient.getStackSectionData(parameters.warp.montageStack,
                                                                                        z,
                                                                                        z,
                                                                                       null);
        if (sectionDataList.size() == 0) {
            throw new IllegalArgumentException("montage stack does not contain any matching z values");
        }

        final ResolvedTileSpecCollection montageTiles =
                montageDataClient.getResolvedTiles(parameters.warp.montageStack, z);
        final ResolvedTileSpecCollection alignTiles =
                alignDataClient.getResolvedTiles(parameters.warp.alignStack, z);

        if (parameters.warp.excludeTilesNotInBothStacks) {
            montageTiles.removeDifferentTileSpecs(alignTiles.getTileIds());
            alignTiles.removeDifferentTileSpecs(montageTiles.getTileIds());
        }

        if (parameters.transformClustersIndependently) {
            buildTransformsForClusters(montageTiles,
                                       alignTiles,
                                       sectionDataList,
                                       matchDataClient,
                                       z,
                                       parameters.tileCluster);
        } else {
            buildTransformForZ(montageTiles,
                               alignTiles,
                               z);
        }

        final int totalNumberOfTiles = montageTiles.getTileCount();
        if (tileSpecValidator != null) {
            montageTiles.setTileSpecValidator(tileSpecValidator);
            montageTiles.removeInvalidTileSpecs();
        }
        final int numberOfRemovedTiles = totalNumberOfTiles - montageTiles.getTileCount();

        LOG.info("generateStackDataForZ: added transform and derived bounding boxes for {} tiles with z of {}, removed {} bad tiles",
                 totalNumberOfTiles, z, numberOfRemovedTiles);

        if (montageTiles.getTileCount() == 0) {
            throw new IllegalStateException("no tiles left to save after filtering invalid tiles for z " + z);
        }

        if (parameters.tileCluster.isDefined()) {
            translateOverlappingSmallClusters(z, montageTiles);
        }

        targetDataClient.saveResolvedTiles(montageTiles, parameters.warp.targetStack, z);

        LOG.info("generateStackDataForZ: exit, saved tiles and transforms for {}", z);
    }

    private void translateOverlappingSmallClusters(final double z,
                                                   final ResolvedTileSpecCollection montageTiles)
            throws IOException {

        final Set<String> stackTileIds = new HashSet<>(montageTiles.getTileIds());

        final TileIdsWithMatches tileIdsWithMatches =
                UnconnectedTileRemovalClient.getTileIdsWithMatches(montageDataClient,
                                                                   parameters.warp.montageStack,
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

        LOG.info("translateOverlappingSmallClusters: for z {}, found {} connected tile sets with sizes {}",
                 z, clusters.size(), clusters.getClusterSizes());

        final List<Set<String>> smallerRemainingClusters =
                UnconnectedTileRemovalClient.markSmallClustersAsUnconnected(parameters.tileCluster,
                                                                            z,
                                                                            sortedConnectedTileIdSets,
                                                                            new HashSet<>(),
                                                                            unconnectedTileIds);

        if (unconnectedTileIds.size() > 0) {
            LOG.info("translateOverlappingSmallClusters: removing {} unconnected tiles from z {}",
                     unconnectedTileIds.size(), z);
            montageTiles.removeTileSpecs(unconnectedTileIds);
        }

        moveSmallClustersForLayer(montageTiles,
                                  z,
                                  sortedConnectedTileIdSets.get(0),
                                  smallerRemainingClusters);

    }

    private void moveSmallClustersForLayer(final ResolvedTileSpecCollection montageTiles,
                                           final Double z,
                                           final Set<String> largestCluster,
                                           final List<Set<String>> smallerRemainingClusters)
            throws IllegalArgumentException {

        LOG.info("moveSmallClustersForLayer: entry for z {}", z);

        final List<TileBounds> tileBoundsList = new ArrayList<>(largestCluster.size());
        Bounds completedBounds = new Bounds();

        for (final String tileId : largestCluster) {
            final TileSpec tileSpec = montageTiles.getTileSpec(tileId);
            final TileBounds tileBounds = tileSpec.toTileBounds();
            tileBoundsList.add(tileBounds);
            completedBounds = completedBounds.union(tileBounds);
        }

        final TileBoundsRTree completedTree = new TileBoundsRTree(z, tileBoundsList);

        for (final Set<String> smallCluster : smallerRemainingClusters) {

                completedBounds = moveCluster(montageTiles,
                                              completedTree,
                                              completedBounds,
                                              smallCluster,
                                              0,
                                              0,
                                              0);
            }

         LOG.info("moveSmallClustersForLayer: exit for z {}", z);
    }

    private Bounds moveCluster(final ResolvedTileSpecCollection montageTiles,
                               final TileBoundsRTree completedTree,
                               final Bounds completedBounds,
                               final Set<String> smallCluster,
                               double moveX,
                               double moveY,
                               final int callDepth) {

        if (callDepth > 5) {

            LOG.warn("moveCluster: after {} attempts for {}, simply moving outside completed bounds",
                     callDepth, getClusterInfo(smallCluster));

            final double[] safeOffsets = getSmallestSafeOffsets(montageTiles, completedBounds, smallCluster, moveX, moveY);
            moveX = safeOffsets[0];
            moveY = safeOffsets[1];
        }

        boolean foundOverlap = false;
        Bounds updatedCompletedBounds = completedBounds;

        Bounds overlappingCompletedRegion = new Bounds();
        Bounds overlappingSmallClusterRegion = new Bounds();

        for (final String tileId : smallCluster) {
            final TileSpec tileSpec = montageTiles.getTileSpec(tileId);
            final double minX = tileSpec.getMinX() + moveX;
            final double minY = tileSpec.getMinY() + moveY;
            final double maxX = tileSpec.getMaxX() + moveX;
            final double maxY = tileSpec.getMaxY() + moveY;

            for (final TileBounds overlappingBounds : completedTree.findTilesInBox(minX, minY, maxX, maxY)) {
                foundOverlap = true;
                overlappingCompletedRegion = overlappingCompletedRegion.union(overlappingBounds);
                overlappingSmallClusterRegion = overlappingSmallClusterRegion.union(new Bounds(minX, minY, maxX, maxY));
            }
        }

        if (foundOverlap) {

            double shiftX;
            if (overlappingSmallClusterRegion.getMinX() < overlappingCompletedRegion.getMinX()) {
                shiftX = overlappingCompletedRegion.getMinX() - overlappingSmallClusterRegion.getMaxX();
            } else {
                shiftX = overlappingCompletedRegion.getMaxX() - overlappingSmallClusterRegion.getMinX();
            }

            double shiftY;
            if (overlappingSmallClusterRegion.getMinY() < overlappingCompletedRegion.getMinY()) {
                shiftY = overlappingCompletedRegion.getMinY() - overlappingSmallClusterRegion.getMaxY();
            } else {
                shiftY = overlappingCompletedRegion.getMaxY() - overlappingSmallClusterRegion.getMinY();
            }

            if (Math.abs(shiftX) < Math.abs(shiftY)) {
                shiftY = 0;
                if (shiftX > 0) {
                    shiftX += SHIFT_OFFSET;
                } else {
                    shiftX -= SHIFT_OFFSET;
                }
            }  else {
                shiftX = 0;
                if (shiftY > 0) {
                    shiftY += SHIFT_OFFSET;
                } else {
                    shiftY -= SHIFT_OFFSET;
                }
            }

            LOG.info("moveCluster: overlap with move ({}, {}) at callDepth {}, shifting additional ({}, {}) for {}",
                     moveX, moveY, callDepth, shiftX, shiftY, getClusterInfo(smallCluster));

            updatedCompletedBounds = moveCluster(montageTiles,
                                                 completedTree,
                                                 completedBounds,
                                                 smallCluster,
                                                 moveX + shiftX,
                                                 moveY + shiftY,
                                                 callDepth + 1);

        } else if ((moveX != 0) || (moveY != 0)) {

            final TranslationModel2D model = new TranslationModel2D();
            model.set(moveX, moveY);
            final String modelDataString = model.toDataString();
            final LeafTransformSpec moveTransform = new LeafTransformSpec(model.getClass().getName(),
                                                                          modelDataString);

            LOG.info("moveCluster: applying translate {} to {}", modelDataString, getClusterInfo(smallCluster));

            for (final String tileId : smallCluster) {
                montageTiles.addTransformSpecToTile(tileId,
                                                    moveTransform,
                                                    ResolvedTileSpecCollection.TransformApplicationMethod.PRE_CONCATENATE_LAST);
                final TileSpec tileSpec = montageTiles.getTileSpec(tileId);
                final TileBounds tileBounds = tileSpec.toTileBounds();
                completedTree.addTile(tileBounds);
                updatedCompletedBounds = updatedCompletedBounds.union(tileBounds);
            }

        } else {

            for (final String tileId : smallCluster) {
                final TileSpec tileSpec = montageTiles.getTileSpec(tileId);
                final TileBounds tileBounds = tileSpec.toTileBounds();
                completedTree.addTile(tileBounds);
                updatedCompletedBounds = updatedCompletedBounds.union(tileBounds);
            }

        }

        return updatedCompletedBounds;
    }

    private String getClusterInfo(final Set<String> clusterTileIds) {
        final String tileId = clusterTileIds.stream().findFirst().orElse("?");
        return clusterTileIds.size() + " tile cluster with tileId " + tileId;
    }

    @SuppressWarnings({"DuplicatedCode", "DuplicateExpressions"})
    private double[] getSmallestSafeOffsets(final ResolvedTileSpecCollection allTiles,
                                            final Bounds completedBounds,
                                            final Set<String> smallCluster,
                                            double moveX,
                                            double moveY) {

        Bounds smallClusterBox = new Bounds();
        for (final String tileId : smallCluster) {
            final TileSpec tileSpec = allTiles.getTileSpec(tileId);
            final Bounds tileBounds = new Bounds(tileSpec.getMinX() + moveX,
                                                 tileSpec.getMinY() + moveY,
                                                 tileSpec.getMaxX() + moveX,
                                                 tileSpec.getMaxY() + moveY);
            smallClusterBox = smallClusterBox.union(tileBounds);
        }

        final double deltaTop = completedBounds.getMinY() - (smallClusterBox.getMaxY() + SHIFT_OFFSET);
        final double deltaBottom = completedBounds.getMaxY() - (smallClusterBox.getMinY() - SHIFT_OFFSET);
        final double deltaLeft = completedBounds.getMinX() - (smallClusterBox.getMaxX() + SHIFT_OFFSET);
        final double deltaRight = completedBounds.getMaxX() - (smallClusterBox.getMinX() - SHIFT_OFFSET);

        if (Math.abs(deltaTop) < Math.abs(deltaBottom)) {

            if (Math.abs(deltaTop) < Math.abs(deltaLeft)) {
                if (Math.abs(deltaTop) < Math.abs(deltaRight)) {
                    moveY += deltaTop;
                } else {
                    moveX += deltaRight;
                }
            } else if (Math.abs(deltaLeft) < Math.abs(deltaRight)) {
                moveX += deltaLeft;
            } else {
                moveX += deltaRight;
            }

        } else if (Math.abs(deltaBottom) < Math.abs(deltaLeft)) {

            if (Math.abs(deltaBottom) < Math.abs(deltaRight)) {
                moveY += deltaBottom;
            } else {
                moveX += deltaRight;
            }

        } else if (Math.abs(deltaLeft) < Math.abs(deltaRight)) {
            moveX += deltaLeft;
        } else {
            moveX += deltaRight;
        }

        return new double[] {moveX, moveY};
    }

    private static void buildTransformForZ(final ResolvedTileSpecCollection montageTiles,
                                           final ResolvedTileSpecCollection alignTiles,
                                           final Double z)
            throws Exception {

        final String transformId = z + "_rigid";

        final TransformSpec warpTransformSpec = buildRigidTransform(montageTiles.getTileSpecs(),
                                                                    alignTiles.getTileSpecs(),
                                                                    transformId);

        // TODO: add support for other transform application methods
        montageTiles.getTileSpecs().forEach(ts -> montageTiles.addTransformSpecToTile(ts.getTileId(),
                                                                                      warpTransformSpec,
                                                                                      PRE_CONCATENATE_LAST));

        LOG.info("buildTransformForZ: processed {} tiles for z {}",
                 montageTiles.getTileCount(), z);

    }

    private static void buildTransformsForClusters(final ResolvedTileSpecCollection montageTiles,
                                                   final ResolvedTileSpecCollection alignTiles,
                                                   final List<SectionData> sectionDataList,
                                                   final RenderDataClient matchDataClient,
                                                   final Double z,
                                                   final TileClusterParameters tileClusterParameters)
            throws Exception {

        final List<CanvasMatches> matchesList = new ArrayList<>();
        for (final SectionData sectionData : sectionDataList) {
            if (z.equals(sectionData.getZ())) {
                if (tileClusterParameters.includeMatchesOutsideGroup) {
                    matchesList.addAll(matchDataClient.getMatchesWithPGroupId(sectionData.getSectionId(), true));
                } else {
                    matchesList.addAll(matchDataClient.getMatchesWithinGroup(sectionData.getSectionId()));
                }
            }
        }

        if (matchesList.size() == 0) {
            throw new IllegalStateException("cannot determine clusters because no matches were found for z " + z);
        }

        final SortedConnectedCanvasIdClusters clusters = new SortedConnectedCanvasIdClusters(matchesList);
        final List<Set<String>> connectedTileSets = clusters.getSortedConnectedTileIdSets();

        LOG.info("buildTransformsForClusters: for z {}, found {} connected tile sets with sizes {}",
                 z, clusters.size(), clusters.getClusterSizes());

        final Set<String> largestCluster = connectedTileSets.get(connectedTileSets.size() - 1);
        final int maxSmallClusterSize = tileClusterParameters.getEffectiveMaxSmallClusterSize(largestCluster.size());

        final int tileCountBeforeRemoval = montageTiles.getTileCount();
        int smallClusterCount = 0;

        final List<Set<String>> largestConnectedTileSets = new ArrayList<>(connectedTileSets.size());
        for (final Set<String> clusterTileIds : connectedTileSets) {

            if (clusterTileIds.size() <= maxSmallClusterSize) {

                montageTiles.removeTileSpecs(clusterTileIds);
                smallClusterCount++;

            } else {

                final int beforeSize = clusterTileIds.size();

                clusterTileIds.removeIf(tileId -> ! montageTiles.hasTileSpec(tileId));

                if (beforeSize > clusterTileIds.size()) {
                    LOG.info("buildTransformsForClusters: removed {} large cluster tiles that have matches but are missing from the montage stack",
                             (beforeSize - clusterTileIds.size()));
                }

                // TODO: add this check to Warp client
                if (clusterTileIds.size() > 0) {
                    largestConnectedTileSets.add(clusterTileIds);
                }
            }
        }

        final int removedTileCount = tileCountBeforeRemoval - montageTiles.getTileCount();

        LOG.info("buildTransformsForClusters: removed {} tiles found in {} small ({}-tile or less) clusters",
                 removedTileCount, smallClusterCount, maxSmallClusterSize);

        // resolve the remaining montage tile spec transform references so that TPS calculations work
        montageTiles.resolveTileSpecs();

        final Collection<TileSpec> alignTileSpecs = alignTiles.getTileSpecs(); // note: resolves align transforms

        int clusterIndex = 0;
        for (final Set<String> clusterTileIds : largestConnectedTileSets) {

            final List<TileSpec> clusterTileSpecs = new ArrayList<>(montageTiles.getTileCount());
            final AtomicInteger alignCount = new AtomicInteger(0);
            clusterTileIds.forEach(tileId -> {
                final TileSpec tileSpec = montageTiles.getTileSpec(tileId);
                if (tileSpec != null) {
                    clusterTileSpecs.add(tileSpec);
                    if (alignTiles.hasTileSpec(tileId)) {
                        alignCount.getAndIncrement();
                    }
                }
            });

            // TODO: simplify Warp client in same way
            if (alignCount.get() < 2) {

                // Saalfeld said that there needs to be at least 3 aligned center points for rigid alignment to work.
                montageTiles.removeTileSpecs(clusterTileIds);

                LOG.info("buildTransformsForClusters: removed {} montage tiles and skipped build for z {} cluster {} because less than 2 of the tiles were found in the align stack, removed tile ids are: {}",
                         clusterTileIds.size(), z, clusterIndex, clusterTileIds);

            } else {

                final String transformId = z + "_cluster_" + clusterIndex + "_rigid";

                final TransformSpec warpTransformSpec = buildRigidTransform(clusterTileSpecs,
                                                                            alignTileSpecs,
                                                                            transformId);

                // TODO: add support for other transform application methods
                clusterTileSpecs.forEach(ts -> montageTiles.addTransformSpecToTile(ts.getTileId(),
                                                                                   warpTransformSpec,
                                                                                   PRE_CONCATENATE_LAST));

                LOG.info("buildTransformsForClusters: processed {} tiles for z {} cluster {}",
                         clusterTileIds.size(), z, clusterIndex);

            }

            clusterIndex++;
        }

    }

    private static TransformSpec buildRigidTransform(final Collection<TileSpec> montageTiles,
                                                     final Collection<TileSpec> alignTiles,
                                                     final String transformId)
            throws Exception {

        LOG.info("buildTransform: deriving transform {}", transformId);

        final AbstractWarpTransformBuilder<? extends CoordinateTransform> transformBuilder =
                new RigidBuilder(montageTiles, alignTiles);

        final CoordinateTransform transform;

        transform = transformBuilder.call();

        LOG.info("buildTransform: completed {} transform derivation", transformId);

        return new LeafTransformSpec(transformId,
                                     null,
                                     transform.getClass().getName(),
                                     transform.toDataString());
    }

    private static final Logger LOG = LoggerFactory.getLogger(RigidTransformClient.class);
    private static final double SHIFT_OFFSET = 20;

}

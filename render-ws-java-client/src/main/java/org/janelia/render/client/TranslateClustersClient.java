package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import mpicbg.trakem2.transform.AffineModel2D;

import org.janelia.alignment.match.SortedConnectedCanvasIdClusters;
import org.janelia.alignment.match.TileIdsWithMatches;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileBoundsRTree;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.TileClusterParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.janelia.alignment.spec.ResolvedTileSpecCollection.TransformApplicationMethod;

/**
 * Java client for translating separate connected clusters in a stack layer so that cluster tiles are near
 * their "ideal" location without overlapping tiles in any other clusters.
 *
 * @author Eric Trautman
 */
public class TranslateClustersClient {

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
                description = "Name of target stack (default is same as source stack)")
        public String targetStack;

        @ParametersDelegate
        TileClusterParameters tileCluster = new TileClusterParameters();

        @Parameter(
                names = "--marginPixels",
                description = "Number of pixels for row and column margin between largest and smaller clusters")
        public Integer marginPixels = 100;

        @Parameter(
                names = "--cellNeighborDistance",
                description = "Distance (in row/column cells) to look for nearby large cluster cells")
        public Integer cellNeighborDistance = 50;

        @Parameter(
                names = "--originOffset",
                description = "Number of pixels to offset each layer from the origin")
        public Integer originOffset = 10;

        @Parameter(
                names = "--excludeTileIdsMissingFromStacks",
                description = "Name(s) of stack(s) that contain ids of tiles to be included in target stack (assumes owner and project are same as source stack).",
                variableArity = true
        )
        public List<String> excludeTileIdsMissingFromStacks;

        @Parameter(
                names = "--completeTargetStack",
                description = "Complete target stack after processing",
                arity = 0)
        public boolean completeTargetStack = false;

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

                final TranslateClustersClient client = new TranslateClustersClient(parameters);
                client.translateLayerClusters();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    private TranslateClustersClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    private void translateLayerClusters()
            throws Exception {

        final RenderDataClient renderDataClient = parameters.renderWeb.getDataClient();

        final RenderDataClient matchDataClient =
                parameters.tileCluster.getMatchDataClient(parameters.renderWeb.baseDataUrl,
                                                          parameters.renderWeb.owner);
        final RenderDataClient targetClient =
                new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                     parameters.getTargetOwner(),
                                     parameters.getTargetProject());

        final StackMetaData sourceStackMetaData = renderDataClient.getStackMetaData(parameters.stack);

        if (parameters.targetStack == null) {
            parameters.targetStack = parameters.stack;
            targetClient.ensureStackIsInLoadingState(parameters.stack, sourceStackMetaData);
        } else {
            targetClient.setupDerivedStack(sourceStackMetaData, parameters.targetStack);
        }

        for (final Double z : parameters.zValues) {

            final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(parameters.stack, z);

            final Set<String> tileIdsToKeep = new HashSet<>();
            String filterStack = null;
            if (parameters.excludeTileIdsMissingFromStacks != null) {

                for (final String tileIdStack : parameters.excludeTileIdsMissingFromStacks) {

                    tileIdsToKeep.addAll(
                            renderDataClient.getTileBounds(tileIdStack, z)
                                    .stream()
                                    .map(TileBounds::getTileId)
                                    .collect(Collectors.toList()));

                    // once a stack with tiles for the current z is found, use that as the filter
                    if (tileIdsToKeep.size() > 0) {
                        filterStack = tileIdStack;
                        break;
                    }
                }

            }

            if (tileIdsToKeep.size() > 0) {
                final int numberOfTilesBeforeFilter = resolvedTiles.getTileCount();
                resolvedTiles.removeDifferentTileSpecs(tileIdsToKeep);
                final int numberOfTilesRemoved = numberOfTilesBeforeFilter - resolvedTiles.getTileCount();
                LOG.info("translateLayerClusters: removed {} tiles not found in {}", numberOfTilesRemoved, filterStack);
            }

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

            LOG.info("translateLayerClusters: for z {}, found {} connected tile sets with sizes {}",
                     z, clusters.size(), clusters.getClusterSizes());

            final List<Set<String>> smallerRemainingClusters =
                    UnconnectedTileRemovalClient.markSmallClustersAsUnconnected(parameters.tileCluster,
                                                                                z,
                                                                                sortedConnectedTileIdSets,
                                                                                new HashSet<>(),
                                                                                unconnectedTileIds);

            if (unconnectedTileIds.size() > 0) {
                LOG.info("translateLayerClusters: removing {} unconnected tiles from z {}",
                         unconnectedTileIds.size(), z);
                resolvedTiles.removeTileSpecs(unconnectedTileIds);
            }

            moveSmallClustersForLayer(resolvedTiles,
                                      z,
                                      sortedConnectedTileIdSets.get(0),
                                      smallerRemainingClusters);

            moveLayerToOrigin(resolvedTiles, z);

            if ((parameters.targetStack != null) && (resolvedTiles.getTileCount() > 0)) {
                targetClient.saveResolvedTiles(resolvedTiles, parameters.targetStack, null);
            }

        }

        if (parameters.completeTargetStack) {
            targetClient.setStackState(parameters.targetStack, StackMetaData.StackState.COMPLETE);
        }

    }

    private void moveSmallClustersForLayer(final ResolvedTileSpecCollection allTiles,
                                           final Double z,
                                           final Set<String> largestCluster,
                                           final List<Set<String>> smallerRemainingClusters)
            throws IllegalArgumentException {

        LOG.info("moveSmallClustersForLayer: entry for z {}", z);

        final List<TileBounds> tileBoundsList = new ArrayList<>(largestCluster.size());
        Bounds completedBounds = new Bounds();
        final List<TileBounds> layoutBoundsList = new ArrayList<>(largestCluster.size());

        for (final String tileId : largestCluster) {
            final TileSpec tileSpec = allTiles.getTileSpec(tileId);

            final TileBounds tileBounds = tileSpec.toTileBounds();
            tileBoundsList.add(tileBounds);
            completedBounds = completedBounds.union(tileBounds);

            final TileBounds layoutBounds = getLayoutBounds(tileSpec);
            layoutBoundsList.add(layoutBounds);
        }

        final TileBoundsRTree layoutTree = new TileBoundsRTree(z, layoutBoundsList);
        final TileBoundsRTree completedTree = new TileBoundsRTree(z, tileBoundsList);

        final double[] rowAndColumnOffsets = getRowAndColumnOffsets(allTiles, z, largestCluster, layoutBoundsList);
        final double rowOffset = rowAndColumnOffsets[0];
        final double columnOffset = rowAndColumnOffsets[1];

        final int marginX = (columnOffset > 0) ? parameters.marginPixels : - parameters.marginPixels;
        final int marginY = (rowOffset > 0) ? parameters.marginPixels : - parameters.marginPixels;

        final double maxDistance = LAYOUT_BOUNDS_CELL_SIZE * parameters.cellNeighborDistance;

        for (final Set<String> smallCluster : smallerRemainingClusters) {

            double minDistance = Double.MAX_VALUE;
            String largeTileId = null;
            String smallTileId = null;

            for (final String tileId : smallCluster) {

                final TileSpec tileSpec = allTiles.getTileSpec(tileId);
                final TileBounds bounds = getLayoutBounds(tileSpec);
                final List<TileBounds> nearestTiles = layoutTree.findTilesNearestToBox(bounds.getMinX(),
                                                                                       bounds.getMinY(),
                                                                                       bounds.getMaxX(),
                                                                                       bounds.getMaxY(),
                                                                                       maxDistance,
                                                                                       1);
                if (nearestTiles.size() > 0) {
                    final TileBounds nearestBounds = nearestTiles.get(0);
                    final double deltaX = nearestBounds.getMinX() - bounds.getMinX();
                    final double deltaY = nearestBounds.getMinY() - bounds.getMinY();
                    final double distance = Math.sqrt((deltaX * deltaX) + (deltaY * deltaY));
                    if (distance < minDistance) {
                        minDistance = distance;
                        largeTileId = nearestBounds.getTileId();
                        smallTileId = tileId;
                    }
                }

            }

            if (largeTileId == null) {
                throw new IllegalArgumentException(
                        "cannot translate clusters for z " + z + " because the " + getClusterInfo(smallCluster) +
                        " is too far (in rows and columns) from tiles in the largest cluster");
            } else {

                final TileSpec largeTileSpec = allTiles.getTileSpec(largeTileId);
                final LayoutData largeLayout = largeTileSpec.getLayout();
                final TileSpec smallTileSpec = allTiles.getTileSpec(smallTileId);
                final LayoutData smallLayout = smallTileSpec.getLayout();
                final int rowDelta = largeLayout.getImageRow() - smallLayout.getImageRow();
                final int columnDelta = largeLayout.getImageCol() - smallLayout.getImageCol();
                final double desiredDeltaX = (columnDelta * columnOffset) + marginX;
                final double desiredDeltaY = (rowDelta * rowOffset) + marginY;
                final double actualDeltaX = largeTileSpec.getMinX() - smallTileSpec.getMinX();
                final double actualDeltaY = largeTileSpec.getMinY() - smallTileSpec.getMinY();
                final double moveX = actualDeltaX - desiredDeltaX;
                final double moveY = actualDeltaY - desiredDeltaY;

                completedBounds = moveCluster(allTiles,
                                              completedTree,
                                              completedBounds,
                                              smallCluster,
                                              moveX,
                                              moveY,
                                              0);
            }

        }

        LOG.info("moveSmallClustersForLayer: exit for z {}", z);
    }

    private TileBounds getLayoutBounds(final TileSpec forTileSpec) {
        final double minX = forTileSpec.getLayout().getImageCol() * LAYOUT_BOUNDS_CELL_SIZE;
        final double minY = forTileSpec.getLayout().getImageRow() * LAYOUT_BOUNDS_CELL_SIZE;
        return new TileBounds(forTileSpec.getTileId(),
                              forTileSpec.getSectionId(),
                              forTileSpec.getZ(),
                              minX,
                              minY,
                              (minX + LAYOUT_BOUNDS_CELL_SIZE),
                              (minY + LAYOUT_BOUNDS_CELL_SIZE));
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

    private Bounds moveCluster(final ResolvedTileSpecCollection allTiles,
                               final TileBoundsRTree completedTree,
                               final Bounds completedBounds,
                               final Set<String> smallCluster,
                               double moveX,
                               double moveY,
                               final int callDepth) {

        if (callDepth > 5) {

            LOG.info("moveCluster: after {} attempts for {}, simply moving outside completed bounds",
                     callDepth, getClusterInfo(smallCluster));

            final double[] safeOffsets = getSmallestSafeOffsets(allTiles, completedBounds, smallCluster, moveX, moveY);
            moveX = safeOffsets[0];
            moveY = safeOffsets[1];
        }

        boolean foundOverlap = false;
        Bounds updatedCompletedBounds = completedBounds;

        double maxShiftX = 0;
        double maxShiftY = 0;

        for (final String tileId : smallCluster) {
            final TileSpec tileSpec = allTiles.getTileSpec(tileId);
            final double minX = tileSpec.getMinX() + moveX;
            final double minY = tileSpec.getMinY() + moveY;
            final double maxX = tileSpec.getMaxX() + moveX;
            final double maxY = tileSpec.getMaxY() + moveY;

            for (final TileBounds overlappingBounds : completedTree.findTilesInBox(minX, minY, maxX, maxY)) {
                foundOverlap = true;

                final double shiftX;
                if (minX < overlappingBounds.getMinX()) {
                    shiftX = overlappingBounds.getMinX() - maxX - SHIFT_OFFSET;
                } else {
                    shiftX = overlappingBounds.getMaxX() - minX + SHIFT_OFFSET;
                }
                if (Math.abs(shiftX) > Math.abs(maxShiftX)) {
                    maxShiftX = shiftX;
                }

                final double shiftY;
                if (minY < overlappingBounds.getMinY()) {
                    shiftY = overlappingBounds.getMinY() - maxY - SHIFT_OFFSET;
                } else {
                    shiftY = overlappingBounds.getMaxY() - minY + SHIFT_OFFSET;
                }
                if (Math.abs(shiftY) > Math.abs(maxShiftY)) {
                    maxShiftY = shiftY;
                }
            }
        }

        if (foundOverlap) {

            LOG.info("moveCluster: found overlap(s) with moveX: {}, moveY: {}, callDepth: {}, for {}",
                     moveX, moveY, callDepth, getClusterInfo(smallCluster));

            updatedCompletedBounds = moveCluster(allTiles,
                                             completedTree,
                                             completedBounds,
                                             smallCluster,
                                             moveX + maxShiftX,
                                             moveY + maxShiftY,
                                             callDepth + 1);
        } else {

            final AffineModel2D model = new AffineModel2D();
            model.set(1, 0, 0, 1, moveX, moveY);
            final String modelDataString = model.toDataString();
            final LeafTransformSpec moveTransform = new LeafTransformSpec(model.getClass().getName(),
                                                                          modelDataString);

            LOG.info("moveCluster: applying affine {} to {}", modelDataString, getClusterInfo(smallCluster));

            for (final String tileId : smallCluster) {
                allTiles.addTransformSpecToTile(tileId,
                                                moveTransform,
                                                TransformApplicationMethod.PRE_CONCATENATE_LAST);
                final TileSpec tileSpec = allTiles.getTileSpec(tileId);
                final TileBounds tileBounds = tileSpec.toTileBounds();
                completedTree.addTile(tileBounds);
                updatedCompletedBounds = updatedCompletedBounds.union(tileBounds);
            }
        }

        return updatedCompletedBounds;
    }

    private double[] getRowAndColumnOffsets(final ResolvedTileSpecCollection allTiles,
                                            final Double z,
                                            final Set<String> largestCluster,
                                            final List<TileBounds> layoutBoundsList)
            throws IllegalArgumentException {

        // sort layer bounds list by row and then column
        layoutBoundsList.sort(
                Comparator.comparingDouble((ToDoubleFunction<TileBounds>) Bounds::getMinY)
                        .thenComparingDouble(Bounds::getMinX));

        final double rowOffset;
        final double columnOffset;

        final int lastIndex = layoutBoundsList.size() - 1;
        if (lastIndex > 0) {
            final TileSpec firstTileSpec = allTiles.getTileSpec(layoutBoundsList.get(0).getTileId());
            final LayoutData firstLayout = firstTileSpec.getLayout();
            final TileSpec lastTileSpec = allTiles.getTileSpec(layoutBoundsList.get(lastIndex).getTileId());
            final LayoutData lastLayout = lastTileSpec.getLayout();
            final double deltaX = firstTileSpec.getMinX() - lastTileSpec.getMinX();
            final double deltaY = firstTileSpec.getMinY() - lastTileSpec.getMinY();
            final int rowDelta = firstLayout.getImageRow() - lastLayout.getImageRow();
            final int columnDelta = firstLayout.getImageCol() - lastLayout.getImageCol();
            if (rowDelta != 0) {
                rowOffset = deltaY / rowDelta;
            } else { // all largest cluster tiles are in same row
                rowOffset = firstTileSpec.getHeight();
            }
            if (columnDelta != 0) {
                columnOffset = deltaX / columnDelta;
            } else { // all largest cluster tiles are in same column
                columnOffset = firstTileSpec.getWidth();
            }
        } else {
            throw new IllegalArgumentException("cannot translate clusters for z " + z +
                                               " because largest cluster only has " + largestCluster.size() +
                                               " tiles");
        }

        LOG.info("getRowAndColumnOffsets: rowOffset {} and columnOffset {} derived for z {}",
                 rowOffset, columnOffset, z);

        return new double[] { rowOffset, columnOffset };
    }

    private String getClusterInfo(final Set<String> clusterTileIds) {
        final String tileId = clusterTileIds.stream().findFirst().orElse("?");
        return clusterTileIds.size() + " tile cluster with tileId " + tileId;
    }

    private void moveLayerToOrigin(final ResolvedTileSpecCollection allTiles,
                                   final Double z)
            throws IllegalArgumentException {

        LOG.info("moveLayerToOrigin: entry for z {}", z);

        Bounds layerBounds = new Bounds();

        for (final TileSpec tileSpec : allTiles.getTileSpecs()) {
            final TileBounds tileBounds = tileSpec.toTileBounds();
            layerBounds = layerBounds.union(tileBounds);
        }

        if (layerBounds.getMinX() != null) {
            if ((layerBounds.getMinX() < 0) || (layerBounds.getMinX() > parameters.originOffset) ||
                (layerBounds.getMinY() < 0) || (layerBounds.getMinY() > parameters.originOffset)) {

                final double moveX = parameters.originOffset - layerBounds.getMinX();
                final double moveY = parameters.originOffset - layerBounds.getMinY();

                final AffineModel2D model = new AffineModel2D();
                model.set(1, 0, 0, 1, moveX, moveY);
                final String modelDataString = model.toDataString();
                final LeafTransformSpec moveTransform = new LeafTransformSpec(model.getClass().getName(),
                                                                              modelDataString);

                LOG.info("moveLayerToOrigin: applying affine {} to all tiles with z {}",
                         modelDataString, z);

                allTiles.preConcatenateTransformToAllTiles(moveTransform);

            } else {
                LOG.info("moveLayerToOrigin: skipping move since layer is already near the origin");
            }
        }

        LOG.info("moveLayerToOrigin: exit for z {}", z);
    }

    private static final Logger LOG = LoggerFactory.getLogger(TranslateClustersClient.class);

    private static final int LAYOUT_BOUNDS_CELL_SIZE = 10;
    private static final double SHIFT_OFFSET = 20;
}

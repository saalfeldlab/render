package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import mpicbg.trakem2.transform.CoordinateTransform;
import mpicbg.trakem2.transform.TranslationModel2D;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.SortedConnectedCanvasIdClusters;
import org.janelia.alignment.match.TileIdsWithMatches;
import org.janelia.alignment.spec.LayoutData;
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

        if (parameters.tileCluster.isDefined()) {
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

        targetDataClient.saveResolvedTiles(montageTiles, parameters.warp.targetStack, z);

        LOG.info("generateStackDataForZ: exit, saved tiles and transforms for {}", z);
    }

    private static void buildTransformForZ(final ResolvedTileSpecCollection montageTiles,
                                           final ResolvedTileSpecCollection alignTiles,
                                           final Double z)
            throws Exception {

        final String transformId = z + "_rigid";

        final TransformSpec rigidTransformSpec = buildRigidTransform(montageTiles.getTileSpecs(),
                                                                     alignTiles.getTileSpecs(),
                                                                     transformId);

        // TODO: add support for other transform application methods
        montageTiles.getTileSpecs().forEach(ts -> montageTiles.addTransformSpecToTile(ts.getTileId(),
                                                                                      rigidTransformSpec,
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

        final TileIdsWithMatches tileIdsWithMatches = new TileIdsWithMatches();
        final Set<String> montageTileIds = montageTiles.getTileIds();

        final List<CanvasMatches> allMatches = new ArrayList<>();
        for (final SectionData sectionData : sectionDataList) {
            if (z.equals(sectionData.getZ())) {
                if (tileClusterParameters.includeMatchesOutsideGroup) {
                    allMatches.addAll(matchDataClient.getMatchesWithPGroupId(sectionData.getSectionId(),
                                                                             true));
                } else {
                    allMatches.addAll(matchDataClient.getMatchesWithinGroup(sectionData.getSectionId(),
                                                                            true));
                }
            }
        }

        // throw out matches for tiles not in the montage stack
        tileIdsWithMatches.addMatches(allMatches, montageTileIds);
        final List<CanvasMatches> matchesList = tileIdsWithMatches.getCanvasMatchesList();

        if (matchesList.size() == 0) {
            throw new IllegalStateException("cannot determine clusters because no matches were found for z " + z);
        }

        final SortedConnectedCanvasIdClusters clusters = new SortedConnectedCanvasIdClusters(matchesList);
        final List<Set<String>> connectedTileSets = clusters.getSortedConnectedTileIdSets();

        LOG.info("buildTransformsForClusters: for z {}, found {} connected tile sets with sizes {}",
                 z, clusters.size(), clusters.getClusterSizes());

        final Set<String> largestCluster = connectedTileSets.get(0);
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

        final SectionStageData sectionStageData = new SectionStageData(sectionDataList);
        final List<Set<String>> unalignedConnectedTileSets = new ArrayList<>(connectedTileSets.size());

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

                // Saalfeld said that there needs to be at least 2 aligned center points for rigid alignment to work.
                unalignedConnectedTileSets.add(clusterTileIds);

            } else {

                final String transformId = z + "_cluster_" + clusterIndex + "_rigid";

                final TransformSpec rigidTransformSpec = buildRigidTransform(clusterTileSpecs,
                                                                             alignTileSpecs,
                                                                             transformId);

                if (sectionStageData.isMissingSections()) {
                    // save tile specs "stage" data before applying rigid transform
                    sectionStageData.addMissingSectionStageData(clusterTileSpecs,
                                                                rigidTransformSpec);
                }

                // TODO: add support for other transform application methods
                clusterTileSpecs.forEach(ts -> montageTiles.addTransformSpecToTile(ts.getTileId(),
                                                                                   rigidTransformSpec,
                                                                                   PRE_CONCATENATE_LAST));

                LOG.info("buildTransformsForClusters: processed {} tiles for z {} cluster {}",
                         clusterTileIds.size(), z, clusterIndex);

            }

            clusterIndex++;
        }

        for (final Set<String> clusterTileIds : unalignedConnectedTileSets) {

            TileSpec clusterTileSpec = null;
            String sectionId = null;
            LargestClusterStageData clusterStageData = null;
            for (final String clusterTileId : clusterTileIds) {
                clusterTileSpec = montageTiles.getTileSpec(clusterTileId);
                sectionId = clusterTileSpec.getLayout().getSectionId();
                clusterStageData = sectionStageData.getStageData(sectionId);
                if (clusterStageData != null) {
                    break;
                }
            }

            if (clusterStageData != null) {
                clusterStageData.moveTilesBasedUponRelativeStagePosition(montageTiles,
                                                                         clusterTileIds,
                                                                         clusterTileSpec);
            } else {
                LOG.warn("buildTransformsForClusters: missing cluster stage data for section {}, skipping transform for {} tile cluster: {}",
                         sectionId, clusterTileIds.size(), clusterTileIds);
            }

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

    private static class SectionStageData {

        private final Set<String> missingSectionIds;
        private final Map<String, LargestClusterStageData> sectionIdStageDataMap;

        private SectionStageData(final List<SectionData> sectionDataList) {
            this.missingSectionIds =
                    sectionDataList.stream().map(SectionData::getSectionId).collect(Collectors.toSet());
            this.sectionIdStageDataMap = new HashMap<>();
        }

        private boolean isMissingSections() {
            return missingSectionIds.size() > 0;
        }

        private LargestClusterStageData getStageData(final String forSectionId) {
            return sectionIdStageDataMap.get(forSectionId);
        }

        private void addMissingSectionStageData(final List<TileSpec> clusterTileSpecs,
                                                final TransformSpec transformSpec) {

            final Map<String, List<TileSpec>> sectionIdToTileSpecs = new HashMap<>();
            clusterTileSpecs.forEach(ts -> {
                final String sectionId = ts.getLayout().getSectionId();
                final List<TileSpec> sectionTileSpecs =
                        sectionIdToTileSpecs.computeIfAbsent(sectionId, list -> new ArrayList<>());
                sectionTileSpecs.add(ts);
            });

            final List<String> sortedSectionIds =
                    sectionIdToTileSpecs.keySet().stream().sorted().collect(Collectors.toList());
            for (final String sectionId : sortedSectionIds) {
                if (missingSectionIds.contains(sectionId)) {

                    final List<TileSpec> sectionTileSpecs = sectionIdToTileSpecs.get(sectionId);
                    sectionIdStageDataMap.put(sectionId, new LargestClusterStageData(sectionTileSpecs,
                                                                                     transformSpec));
                    missingSectionIds.remove(sectionId);

                    LOG.info("addMissingSectionStageData: stage data saved for section {}", sectionId);
                }
            }

        }

    }

    private static class LargestClusterStageData {

        private final TransformSpec largestClusterTransformSpec;

        private TileBoundsRTree layoutTree;
        private Map<String, TileBounds> tileIdToSourceBoundsMap;
        private Map<String, LayoutData> tileIdToSourceLayoutMap;

        private double rowDeltaY;
        private double columnDeltaX;

        private LargestClusterStageData(final List<TileSpec> clusterTileSpecs,
                                        final TransformSpec transformSpec) {

            this.largestClusterTransformSpec = transformSpec;

            if (clusterTileSpecs.size() > 1) {
                this.setSourceBoundsInfo(clusterTileSpecs);
                this.setDeltaXAndY(clusterTileSpecs);
            }

        }

        private void setSourceBoundsInfo(final List<TileSpec> clusterTileSpecs) {

            this.tileIdToSourceBoundsMap = new HashMap<>();
            this.tileIdToSourceLayoutMap = new HashMap<>();

            final List<TileBounds> layoutBoundsList = new ArrayList<>(clusterTileSpecs.size());

            TileBounds layoutBounds = null;
            for (final TileSpec tileSpec : clusterTileSpecs) {
                final TileBounds tileBounds = tileSpec.toTileBounds();
                tileIdToSourceBoundsMap.put(tileSpec.getTileId(), tileBounds);
                tileIdToSourceLayoutMap.put(tileSpec.getTileId(), tileSpec.getLayout());
                layoutBounds = getLayoutBounds(tileSpec);
                layoutBoundsList.add(layoutBounds);
            }

            //noinspection ConstantConditions
            this.layoutTree = new TileBoundsRTree(layoutBounds.getZ(), layoutBoundsList);
        }

        private void setDeltaXAndY(final List<TileSpec> clusterTileSpecs) {

            final TileSpec firstTileSpec = clusterTileSpecs.get(0);
            final LayoutData firstLayout = firstTileSpec.getLayout();
            final int row = firstLayout.getImageRow();
            final int column = firstLayout.getImageCol();
            final TileBounds firstLayoutBounds = getLayoutBounds(firstTileSpec);
            final List<TileBounds> nearestLayoutBoundsList =
                    layoutTree.findTilesNearestToBox(firstLayoutBounds.getMinX(), firstLayoutBounds.getMinY(),
                                                     firstLayoutBounds.getMaxX(), firstLayoutBounds.getMaxY(),
                                                     100000, 100);

            for (final TileBounds nearestLayoutBounds : nearestLayoutBoundsList) {

                final String nearestTileId = nearestLayoutBounds.getTileId();
                final TileBounds nearestTileBounds = tileIdToSourceBoundsMap.get(nearestTileId);
                final LayoutData nearestLayout = tileIdToSourceLayoutMap.get(nearestTileId);

                final int rowB = nearestLayout.getImageRow();
                final int columnB = nearestLayout.getImageCol();

                if ((row != rowB) && (column != columnB)) {

                    final TileBounds tileBounds = firstTileSpec.toTileBounds();
                    final double rowDelta = row - rowB;
                    final double columnDelta = column - columnB;
                    final double deltaX = tileBounds.getCenterX() - nearestTileBounds.getCenterX();
                    final double deltaY = tileBounds.getCenterY() - nearestTileBounds.getCenterY();

                    this.rowDeltaY = deltaY / rowDelta;
                    this.columnDeltaX = deltaX / columnDelta;

                    LOG.info("LargestClusterStageData: columnDeltaX {}, rowDeltaY {} derived from tiles {} and {}",
                             (int) columnDeltaX, (int) rowDeltaY, firstTileSpec.getTileId(), nearestTileBounds.getTileId());

                    break;
                }

            }

        }

        private TileBounds getLayoutBounds(final TileSpec forTileSpec) {
            final int cell_size = 10;
            final LayoutData layout = forTileSpec.getLayout();
            final double minX = layout.getImageCol() * cell_size;
            final double minY = layout.getImageRow() * cell_size;
            return new TileBounds(forTileSpec.getTileId(),
                                  forTileSpec.getSectionId(),
                                  forTileSpec.getZ(),
                                  minX,
                                  minY,
                                  (minX + cell_size),
                                  (minY + cell_size));
        }

        private LeafTransformSpec getRelativeStageTransform(final TileSpec clusterTileSpec) {

            final TileBounds layoutBounds = getLayoutBounds(clusterTileSpec);
            final List<TileBounds> nearestLayoutBounds =
                    layoutTree.findTilesNearestToBox(layoutBounds.getMinX(), layoutBounds.getMinY(),
                                                     layoutBounds.getMaxX(), layoutBounds.getMaxY(),
                                                     100000, 1);
            final String nearestTileId = nearestLayoutBounds.get(0).getTileId();
            final TileBounds nearestTileBounds = tileIdToSourceBoundsMap.get(nearestTileId);
            final LayoutData nearestLayout = tileIdToSourceLayoutMap.get(nearestTileId);
            final int row = nearestLayout.getImageRow();
            final int column = nearestLayout.getImageCol();

            final LayoutData layoutB = clusterTileSpec.getLayout();
            final int rowB = layoutB.getImageRow();
            final int columnB = layoutB.getImageCol();
            final double rowDelta = nearestLayout.getImageRow() - rowB;
            final double columnDelta = nearestLayout.getImageCol() - columnB;
            final double stageX = nearestTileBounds.getMinX() - (columnDelta * columnDeltaX);
            final double stageY = nearestTileBounds.getMinY() - (rowDelta * rowDeltaY);

            LOG.info("getRelativeStageTransform: given large cluster tile {} in column {}, row {} is at ({}, {}), estimated position for tile {} in column {}, row {} is ({}, {})",
                     nearestTileId, column, row, nearestTileBounds.getMinX(), nearestTileBounds.getMinY(),
                     clusterTileSpec.getTileId(), columnB, rowB, stageX, stageY);

            final double tx = stageX - clusterTileSpec.getMinX();
            final double ty = stageY - clusterTileSpec.getMinY();

            final TranslationModel2D model = new TranslationModel2D();
            model.set(tx, ty);

            return new LeafTransformSpec(model.getClass().getName(),
                                         model.toDataString());
        }

        private void moveTilesBasedUponRelativeStagePosition(final ResolvedTileSpecCollection montageTiles,
                                                             final Set<String> clusterTileIds,
                                                             final TileSpec clusterTileSpec) {

            LOG.info("moveTilesBasedUponRelativeStagePosition: transforming {} montage tiles: {}",
                     clusterTileIds.size(), clusterTileIds);

            final LeafTransformSpec relativeStageTransform = getRelativeStageTransform(clusterTileSpec);

            for (final String tileId : clusterTileIds) {
                montageTiles.addTransformSpecToTile(tileId,
                                                    relativeStageTransform,
                                                    PRE_CONCATENATE_LAST);
                montageTiles.addTransformSpecToTile(tileId,
                                                    largestClusterTransformSpec,
                                                    PRE_CONCATENATE_LAST);
            }

        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(RigidTransformClient.class);

}

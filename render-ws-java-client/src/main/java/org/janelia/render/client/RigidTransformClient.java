package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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

        LargestClusterStageData largestClusterStageData = null;
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

                if (largestClusterStageData == null) {
                    // derive largest cluster stage data before applying
                    largestClusterStageData = new LargestClusterStageData(clusterTileSpecs,
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
            if (largestClusterStageData != null) {
                largestClusterStageData.moveTilesBasedUponRelativeStagePosition(montageTiles,
                                                                                clusterTileIds);
            } else {
                LOG.warn("buildTransformsForClusters: missing largest cluster info, skipping transform for {} tile cluster: {}",
                         clusterTileIds.size(), clusterTileIds);
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

    private static class LargestClusterStageData {

        private final TransformSpec largestClusterTransformSpec;

        private TileBounds tileBounds;
        private int row;
        private int column;
        private double rowDeltaY;
        private double columnDeltaX;

        private LargestClusterStageData(final List<TileSpec> clusterTileSpecs,
                                        final TransformSpec transformSpec) {

            this.largestClusterTransformSpec = transformSpec;
            this.tileBounds = null;

            if (clusterTileSpecs.size() > 1) {

                final TileSpec firstTileSpec = clusterTileSpecs.get(0);
                final LayoutData layout = firstTileSpec.getLayout();

                this.row = layout.getImageRow();
                this.column = layout.getImageCol();

                for (final TileSpec tileSpec : clusterTileSpecs) {

                    final LayoutData layoutB = tileSpec.getLayout();
                    final int rowB = layoutB.getImageRow();
                    final int columnB = layoutB.getImageCol();

                    if ((this.row != rowB) && (this.column != columnB)) {

                        this.tileBounds = firstTileSpec.toTileBounds();
                        final TileBounds tileBoundsB = tileSpec.toTileBounds();

                        final double rowDelta = this.row - rowB;
                        final double columnDelta = this.column - columnB;
                        final double deltaX = tileBounds.getCenterX() - tileBoundsB.getCenterX();
                        final double deltaY = tileBounds.getCenterY() - tileBoundsB.getCenterY();

                        this.rowDeltaY = deltaY / rowDelta;
                        this.columnDeltaX = deltaX / columnDelta;

                        LOG.info("LargestClusterStageData: columnDeltaX {}, rowDeltaY {} derived from tiles {} and {}",
                                 (int) columnDeltaX, (int) rowDeltaY, firstTileSpec.getTileId(), tileSpec.getTileId());

                        break;
                    }

                }

            }

        }

        private LeafTransformSpec getRelativeStageTransform(final TileSpec clusterTileSpec) {

            final LayoutData layoutB = clusterTileSpec.getLayout();
            final int rowB = layoutB.getImageRow();
            final int columnB = layoutB.getImageCol();
            final double rowDelta = row - rowB;
            final double columnDelta = column - columnB;
            final double stageX = tileBounds.getMinX() - (columnDelta * columnDeltaX);
            final double stageY = tileBounds.getMinY() - (rowDelta * rowDeltaY);

            LOG.info("getRelativeStageTransform: given large cluster tile {} in column {}, row {} is at ({}, {}), estimated position for tile {} in column {}, row {} is ({}, {})",
                     tileBounds.getTileId(), column, row, tileBounds.getMinX(), tileBounds.getMinY(),
                     clusterTileSpec.getTileId(), columnB, rowB, stageX, stageY);

            final double tx = stageX - clusterTileSpec.getMinX();
            final double ty = stageY - clusterTileSpec.getMinY();

            final TranslationModel2D model = new TranslationModel2D();
            model.set(tx, ty);

            return new LeafTransformSpec(model.getClass().getName(),
                                         model.toDataString());
        }

        private void moveTilesBasedUponRelativeStagePosition(final ResolvedTileSpecCollection montageTiles,
                                                             final Set<String> clusterTileIds) {

            if (tileBounds == null) {

                LOG.warn("moveTilesBasedUponRelativeStagePosition: no relative position data, skipping transform of {} montage tiles: {}",
                         clusterTileIds.size(), clusterTileIds);

            } else {

                LOG.info("moveTilesBasedUponRelativeStagePosition: transforming {} montage tiles: {}",
                         clusterTileIds.size(), clusterTileIds);

                LeafTransformSpec relativeStageTransform = null;

                for (final String tileId : clusterTileIds) {
                    if (relativeStageTransform == null) {
                        relativeStageTransform = getRelativeStageTransform(montageTiles.getTileSpec(tileId));
                    }
                    montageTiles.addTransformSpecToTile(tileId,
                                                        relativeStageTransform,
                                                        PRE_CONCATENATE_LAST);
                    montageTiles.addTransformSpecToTile(tileId,
                                                        largestClusterTransformSpec,
                                                        PRE_CONCATENATE_LAST);
                }

            }
        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(RigidTransformClient.class);

}

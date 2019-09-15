package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import mpicbg.trakem2.transform.CoordinateTransform;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.SortedConnectedCanvasIdClusters;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.validator.TileSpecValidator;
import org.janelia.alignment.warp.AbstractWarpTransformBuilder;
import org.janelia.alignment.warp.ThinPlateSplineBuilder;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.TileClusterParameters;
import org.janelia.render.client.parameter.TileSpecValidatorParameters;
import org.janelia.render.client.parameter.WarpStackParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for generating warp transform (TPS or MLS) stack data.
 *
 * @author Eric Trautman
 */
public class WarpTransformClient {

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
                parameters.tileCluster.validate();
                parameters.warp.initDefaultValues(parameters.renderWeb);

                LOG.info("runClient: entry, parameters={}", parameters);

                final WarpTransformClient client = new WarpTransformClient(parameters);

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

    private WarpTransformClient(final Parameters parameters) {
        this.parameters = parameters;
        this.tileSpecValidator = parameters.tileSpecValidator.getValidatorInstance();
        this.montageDataClient = parameters.renderWeb.getDataClient();
        this.alignDataClient = parameters.warp.getAlignDataClient();
        this.targetDataClient = parameters.warp.getTargetDataClient();
        this.matchDataClient =
                parameters.tileCluster.getMatchDataClient(parameters.renderWeb.baseDataUrl,
                                                          parameters.renderWeb.owner);

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

    public static void buildTransformForZ(final ResolvedTileSpecCollection montageTiles,
                                          final ResolvedTileSpecCollection alignTiles,
                                          final Double z)
            throws Exception {

        final String transformId = z + "_TPS";
        final TransformSpec warpTransformSpec = buildTransform(montageTiles.getTileSpecs(),
                                                               alignTiles.getTileSpecs(),
                                                               transformId);

        montageTiles.addTransformSpecToCollection(warpTransformSpec);
        montageTiles.addReferenceTransformToAllTiles(warpTransformSpec.getId(), false);

        LOG.info("buildTransformForZ: processed {} tiles for z {}",
                 montageTiles.getTileCount(), z);

    }

    public static void buildTransformsForClusters(final ResolvedTileSpecCollection montageTiles,
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

                largestConnectedTileSets.add(clusterTileIds);
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

            if (clusterTileSpecs.size() == 0) {

                LOG.info("buildTransformsForClusters: skipped build for z {} cluster {} because none of the {} tiles were found in the montage stack, missing tile ids are: {}",
                         z, clusterIndex, clusterTileIds.size(), clusterTileIds);

            } else if (alignCount.get() < 3) {

                // Saalfeld said that there needs to be at least 3 aligned center points for TPS to work.
                // He later clarified that the points must also not be co-linear, but we're not going to check for that here.
                montageTiles.removeTileSpecs(clusterTileIds);

                LOG.info("buildTransformsForClusters: removed {} montage tiles and skipped build for z {} cluster {} because less than 3 of the tiles were found in the align stack, removed tile ids are: {}",
                         clusterTileIds.size(), z, clusterIndex, clusterTileIds);

            } else {

                final String transformId = z + "_cluster_" + clusterIndex + "_TPS";

                final TransformSpec warpTransformSpec = buildTransform(clusterTileSpecs,
                                                                       alignTileSpecs,
                                                                       transformId);
                montageTiles.addTransformSpecToCollection(warpTransformSpec);
                montageTiles.addReferenceTransformToTilesWithIds(warpTransformSpec.getId(), clusterTileIds, false);

                LOG.info("buildTransformsForClusters: processed {} tiles for z {} cluster {}",
                         clusterTileIds.size(), z, clusterIndex);

            }

            clusterIndex++;
        }

    }

    private static TransformSpec buildTransform(final Collection<TileSpec> montageTiles,
                                                final Collection<TileSpec> alignTiles,
                                                final String transformId)
            throws Exception {

        LOG.info("buildTransform: deriving transform {}", transformId);

        final AbstractWarpTransformBuilder<? extends CoordinateTransform> transformBuilder =
                new ThinPlateSplineBuilder(montageTiles, alignTiles);

        final CoordinateTransform transform;

        transform = transformBuilder.call();

        LOG.info("buildTransform: completed {} transform derivation", transformId);

        return new LeafTransformSpec(transformId,
                                     null,
                                     transform.getClass().getName(),
                                     transform.toDataString());
    }

    private static final Logger LOG = LoggerFactory.getLogger(WarpTransformClient.class);
}

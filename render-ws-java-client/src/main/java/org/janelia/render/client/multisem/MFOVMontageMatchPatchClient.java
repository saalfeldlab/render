package org.janelia.render.client.multisem;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.multisem.MultiSemUtilities;
import org.janelia.alignment.multisem.StackMFOVWithZValues;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileBoundsRTree;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.util.FileUtil;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MFOVMontageMatchPatchParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.ParametersDelegate;

/**
 * Java client for patching matches missing from adjacent SFOV tile pairs within the same MFOV and z layer.
 * <br/><br/>
 * The client:
 * <ul>
 *   <li>
 *     Finds adjacent SFOV tile pairs within the same MFOV and z layer that remain unconnected
 *     after standard matching (typically because of substrate or resin borders).
 *   </li>
 *   <li>
 *     Step 1: If a sameLayerDerivedMatchWeight is specified and unconnected pairs exist,
 *     the client loops through each unconnected pair and tries to find existing matches for that same pair in another
 *     MFOV in the same layer.  If found, the existing matches are copied to the unconnected pair and stored
 *     with the sameLayerDerivedMatchWeight which is typically some reduced value like 0.15
 *     to ensure that standard matches are given precedence.
 *   </li>
 *   <li>
 *     Step 2: If a crossLayerDerivedMatchWeight is specified and unconnected pairs remain after step 1,
 *     the client collects matches for the unconnected pair in all other layers and fits them to a
 *     "montage patch match model".  Montage patch matches are derived by applying the model to each SFOV tile's
 *     corners and those matches are stored with the crossLayerDerivedMatchWeight.
 *   </li>
 *   <li>
 *     Step 3: If a startPositionMatchWeight is specified and unconnected pairs remain after step 2,
 *     point matches are set at the corners of the area where the two tiles overlap in the acquisition stack.
 *   </li>
 * </ul>
 *
 * @author Stephan Preibisch
 * @author Eric Trautman
 */
public class MFOVMontageMatchPatchClient {

    public static class Parameters
            extends CommandLineParameters {

        @ParametersDelegate
        public MultiProjectParameters multiProject = new MultiProjectParameters();

        @ParametersDelegate
        public MFOVMontageMatchPatchParameters patch = new MFOVMontageMatchPatchParameters();

        public Parameters() {
        }

    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args)
                    throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final MFOVMontageMatchPatchClient client = new MFOVMontageMatchPatchClient(parameters);
                client.deriveAndSaveMatchesForAllUnconnectedPairs();
            }
        };
        clientRunner.run();
    }

    private final MultiProjectParameters multiProject;
    private MFOVMontageMatchPatchParameters patch;

    public MFOVMontageMatchPatchClient(final Parameters parameters) {
        this.multiProject = parameters.multiProject;
        this.patch = parameters.patch;
    }

    public void deriveAndSaveMatchesForAllUnconnectedPairs()
            throws Exception {

        final RenderDataClient defaultDataClient = multiProject.getDataClient();
        final List<StackMFOVWithZValues> stackMFOVWithZValuesList =
                multiProject.buildListOfStackMFOVWithAllZ(patch.getMultiFieldOfViewId());

        for (final StackMFOVWithZValues stackMFOVWithZValues : stackMFOVWithZValuesList) {
            final StackId stackId = stackMFOVWithZValues.getStackId();
            this.patch = this.patch.withMultiFieldOfViewId(stackMFOVWithZValues.getmFOVId());
            final MatchCollectionId matchCollectionId = multiProject.getMatchCollectionIdForStack(stackId);
            final String matchStorageCollectionName = patch.getMatchStorageCollectionName(matchCollectionId);
            deriveAndSaveMatchesForUnconnectedPairsInStack(defaultDataClient,
                                                           stackMFOVWithZValues,
                                                           matchCollectionId,
                                                           matchStorageCollectionName);
        }
    }

    /**
     * Derives and saves matches for all unconnected pairs in the specified stack MFOV.
     *
     * @return the number of tile pairs that had derived matches saved.
     */
    public int deriveAndSaveMatchesForUnconnectedPairsInStack(final RenderDataClient defaultDataClient,
                                                              final StackMFOVWithZValues stackMFOVWithZValues,
                                                              final MatchCollectionId matchCollectionId,
                                                              final String matchStorageCollectionName)
            throws IOException, IllegalStateException {

        // TODO: revisit this when there is more time to see if the logic can be simplified ( see https://github.com/saalfeldlab/render/pull/206#pullrequestreview-2686939433 )
        LOG.info("deriveAndSaveMatchesForUnconnectedPairsInStack: entry, stackMFOVWithZValues={}", stackMFOVWithZValues);

        if (! stackMFOVWithZValues.getmFOVId().equals(patch.multiFieldOfViewId)) {
            throw new IllegalStateException("specified mFOVId " + stackMFOVWithZValues.getmFOVId() +
                                            " differs from --multiFieldOfViewId " +
                                            patch.multiFieldOfViewId);
        }

        final StackId stackId = stackMFOVWithZValues.getStackId();
        final String stack = stackId.getStack();

        final RenderDataClient renderDataClient = defaultDataClient.buildClient(stackId.getOwner(),
                                                                                stackId.getProject());
        final RenderDataClient matchClient = renderDataClient.buildClient(matchCollectionId.getOwner(),
                                                                          matchCollectionId.getName());

        final Map<Double, Set<String>> zToSectionIdsMap =
                renderDataClient.getStackZToSectionIdsMap(stack,
                                                          null,
                                                          null,
                                                          stackMFOVWithZValues.getzValues());

        final Map<MFOVPositionPair, MFOVPositionPairMatchData> positionToPairs = new HashMap<>();

        for (final Double z : zToSectionIdsMap.keySet().stream().sorted().collect(Collectors.toList())) {
            final Set<String> sectionIds = zToSectionIdsMap.get(z);
            updatePositionPairDataForZ(stack,
                                       z,
                                       sectionIds,
                                       positionToPairs,
                                       renderDataClient,
                                       matchClient);
        }

        final int totalNumberOfPositions = positionToPairs.size();
        final Set<MFOVPositionPair> positionsWithoutAnyUnconnectedPairs = new HashSet<>();
        for (final MFOVPositionPair positionPair : positionToPairs.keySet()) {
            final MFOVPositionPairMatchData positionPairMatchData = positionToPairs.get(positionPair);
            if (! positionPairMatchData.hasUnconnectedPairs()) {
                positionsWithoutAnyUnconnectedPairs.add(positionPair);
            }
        }
        for (final MFOVPositionPair positionPair : positionsWithoutAnyUnconnectedPairs) {
            positionToPairs.remove(positionPair);
        }

        LOG.info("deriveAndSaveMatchesForUnconnectedPairsInStack: {} out of {} positions in {} have at least one unconnected pair",
                 positionToPairs.size(), totalNumberOfPositions, stackMFOVWithZValues);

        final List<CanvasMatches> derivedMatchesForMFOV = new ArrayList<>();

        final List<MFOVPositionPair> sortedPositions =
                positionToPairs.keySet().stream().sorted().collect(Collectors.toList());
        for (final MFOVPositionPair positionPair : sortedPositions) {
            final MFOVPositionPairMatchData positionPairMatchData = positionToPairs.get(positionPair);
            derivedMatchesForMFOV.addAll(
                    positionPairMatchData.deriveMatchesForUnconnectedPairs(matchClient,
                                                                           patch.sameLayerDerivedMatchWeight,
                                                                           patch.crossLayerDerivedMatchWeight,
                                                                           patch.startPositionMatchWeight));
        }

        final int numberOfDerivedMatchPairs = derivedMatchesForMFOV.size();
        if (numberOfDerivedMatchPairs > 0) {

            final String firstPairKey = derivedMatchesForMFOV.get(0).toKeyString();
            LOG.info("deriveAndSaveMatchesForUnconnectedPairsInStack: saving matches for {} pairs in {}, first save pair is {}",
                     numberOfDerivedMatchPairs, stackMFOVWithZValues, firstPairKey);

            if (patch.matchStorageFile != null) {
                final Path storagePath = Paths.get(patch.matchStorageFile).toAbsolutePath();
                FileUtil.saveJsonFile(storagePath.toString(), derivedMatchesForMFOV);
            } else {
                final RenderDataClient matchStorageClient = matchClient.buildClient(matchCollectionId.getOwner(),
                                                                                    matchStorageCollectionName);
                matchStorageClient.saveMatches(derivedMatchesForMFOV);
            }

        } else {
            LOG.info("deriveAndSaveMatchesForUnconnectedPairsInStack: no pairs have matches in {} so there is nothing to save",
                     stackMFOVWithZValues);
        }

        LOG.info("deriveAndSaveMatchesForUnconnectedPairsInStack: exit, returning {} for {}",
                 numberOfDerivedMatchPairs, stackMFOVWithZValues);

        return derivedMatchesForMFOV.size();
    }
    
    public void updatePositionPairDataForZ(final String stack,
                                           final Double z,
                                           final Set<String> sectionIds,
                                           final Map<MFOVPositionPair, MFOVPositionPairMatchData> positionToPairs,
                                           final RenderDataClient renderDataClient,
                                           final RenderDataClient matchClient)
            throws IOException {

        LOG.info("updatePositionPairDataForZ: entry, stack={}, z={}, sectionIds={}, pMagcMfovSfovPrefix={}, qMagcMfovSfovPrefix={}",
                 stack, z, sectionIds, patch.pMagcMfovSfovPrefix, patch.qMagcMfovSfovPrefix);

        final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(stack, z);

        final List<TileBounds> tileBoundsList =
                resolvedTiles.getTileSpecs().stream().map(TileSpec::toTileBounds).collect(Collectors.toList());
        final TileBoundsRTree tree = new TileBoundsRTree(z, tileBoundsList);

        final Set<OrderedCanvasIdPair> potentialPairsForZ = tree.getCircleNeighbors(tileBoundsList,
                                                                                    new ArrayList<>(),
                                                                                    patch.xyNeighborFactor,
                                                                                    null,
                                                                                    false,
                                                                                    false,
                                                                                    false,
                                                                                    false);

        // add all MFOV tile pairs to unconnected set to start
        final Set<OrderedCanvasIdPair> unconnectedPairsForMFOV = new HashSet<>(potentialPairsForZ.size());
        final Set<String> mfovTileIds = new HashSet<>();
        for (final OrderedCanvasIdPair pair : potentialPairsForZ) {

            final CanvasId p = pair.getP();
            final String pId = p.getId();
            final String pMagcMfovSfov = MultiSemUtilities.getMagcMfovSfovForTileId(pId);

            final CanvasId q = pair.getQ();
            final String qId = q.getId();
            final String qMagcMfovSfov = MultiSemUtilities.getMagcMfovSfovForTileId(qId);

            if (pMagcMfovSfov.startsWith(patch.pMagcMfovSfovPrefix) &&
                qMagcMfovSfov.startsWith(patch.qMagcMfovSfovPrefix)) {

                mfovTileIds.add(pId);
                mfovTileIds.add(qId);

                // remove relative position info from tree search to simplify existence check later
                final OrderedCanvasIdPair pairWithoutRelative =
                        new OrderedCanvasIdPair(p.withoutRelativePosition(),
                                                q.withoutRelativePosition(),
                                                0.0);
                final MFOVPositionPair positionPair = new MFOVPositionPair(pairWithoutRelative);

                final MFOVPositionPairMatchData positionPairMatchData =
                        positionToPairs.computeIfAbsent(positionPair,
                                                        d -> new MFOVPositionPairMatchData(positionPair));

                positionPairMatchData.addPair(pairWithoutRelative,
                                              resolvedTiles.getTileSpec(pId),
                                              resolvedTiles.getTileSpec(qId));

                unconnectedPairsForMFOV.add(pairWithoutRelative);
            }
        }

        LOG.info("updatePositionPairDataForZ: found {} tiles and {} pairs within mFOV {} in z {}",
                 mfovTileIds.size(), unconnectedPairsForMFOV.size(), patch.multiFieldOfViewId, z);

        // query web service to find connected tile pairs and remove them from unconnected set
        final Map<String, OrderedCanvasIdPair> sameLayerPairsFromOtherMFOVs = new HashMap<>();
        int numberOfConnectedPairsForMFOV = 0;
        if (! unconnectedPairsForMFOV.isEmpty()) {

            for (final String groupId : sectionIds) {
                for (final CanvasMatches canvasMatches : matchClient.getMatchesWithinGroup(groupId,
                                                                                           true)) {
                    final String pId = canvasMatches.getpId();
                    final String qId = canvasMatches.getqId();
                    final String pMagcMfovSfov = MultiSemUtilities.getMagcMfovSfovForTileId(pId);
                    final String qMagcMfovSfov = MultiSemUtilities.getMagcMfovSfovForTileId(qId);
                    final OrderedCanvasIdPair pair = new OrderedCanvasIdPair(new CanvasId(groupId, pId),
                                                                             new CanvasId(groupId, qId),
                                                                             0.0);
                    if (pMagcMfovSfov.startsWith(patch.pMagcMfovSfovPrefix) &&
                        qMagcMfovSfov.startsWith(patch.qMagcMfovSfovPrefix)) {
                        if (unconnectedPairsForMFOV.remove(pair)) {
                            numberOfConnectedPairsForMFOV++;
                        } else {
                            LOG.warn("updatePositionPairDataForZ: failed to locate existing pair {} in potential set",
                                     pair);
                        }
                    } else {
                        // note that same layer pair will often come from last MFOV (e.g. MFOV 19 for wafer 53)
                        final String sfovIndexPairName = MultiSemUtilities.getSFOVIndexPairName(canvasMatches.getpGroupId(),
                                                                                                pId,
                                                                                                qId);
                        sameLayerPairsFromOtherMFOVs.put(sfovIndexPairName, pair);
                    }
                }
            }
        }

        final String trimStack = patch.getTrimStackName(stack);

        LOG.info("updatePositionPairDataForZ: found {} connected pairs and {} unconnected pairs within mFOV {} in z {}, trimStack={}",
                 numberOfConnectedPairsForMFOV, unconnectedPairsForMFOV.size(), patch.multiFieldOfViewId, z, trimStack);

        if (trimStack != null) {

            if (numberOfConnectedPairsForMFOV == 0) {
                LOG.info("updatePositionPairDataForZ: all pairs in mFOV {} of z {} are unconnected, excluding this MFOV from the {} stack and skipping patch",
                         patch.multiFieldOfViewId, z, trimStack);
                unconnectedPairsForMFOV.clear();
            } else {
                LOG.info("updatePositionPairDataForZ: since {} connected pairs were found for mFOV {} in z {}, copying all {} tiles to the {} stack",
                         numberOfConnectedPairsForMFOV, patch.multiFieldOfViewId, z, mfovTileIds.size(), trimStack);
                final ResolvedTileSpecCollection mfovResolvedTiles = resolvedTiles.copyAndRetainTileSpecs(mfovTileIds);
                renderDataClient.saveResolvedTiles(mfovResolvedTiles, trimStack, z);
            }

        }

        for (final OrderedCanvasIdPair unconnectedPair : unconnectedPairsForMFOV) {

            final MFOVPositionPair positionPair = new MFOVPositionPair(unconnectedPair);
            final MFOVPositionPairMatchData positionPairMatchData = positionToPairs.get(positionPair);
            positionPairMatchData.addUnconnectedPair(unconnectedPair);

            // try to add same layer pair data from another MFOV, unless we are patching with stage coordinates
            if (! patch.patchAllUnconnectedPairsWithStageCoordinates) {

                final CanvasId p = unconnectedPair.getP();
                final String indexPairName = MultiSemUtilities.getSFOVIndexPairName(p.getGroupId(),
                                                                                    p.getId(),
                                                                                    unconnectedPair.getQ().getId());
                final OrderedCanvasIdPair sameLayerPair = sameLayerPairsFromOtherMFOVs.get(indexPairName);
                if (sameLayerPair == null) {
                    LOG.info("updatePositionPairDataForZ: no same layer pair found for unconnected pair {}",
                             unconnectedPair);
                } else {
                    LOG.info("updatePositionPairDataForZ: using same layer pair {} for unconnected pair {}",
                             sameLayerPair, unconnectedPair);
                    positionPairMatchData.addSameLayerPair(sameLayerPair);
                }

            }
        }

        LOG.info("updatePositionPairDataForZ: exit, found {} unconnected tile pairs within mFOV {} in z {}",
                 unconnectedPairsForMFOV.size(), patch.multiFieldOfViewId, z);
    }

    private static final Logger LOG = LoggerFactory.getLogger(MFOVMontageMatchPatchClient.class);
}

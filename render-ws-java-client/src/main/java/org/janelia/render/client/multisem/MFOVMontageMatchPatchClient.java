package org.janelia.render.client.multisem;

import com.beust.jcommander.ParametersDelegate;

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
 *     For each unconnected pair, the client first fetches any existing standard matches for the
 *     same SFOV pair in other z layers in the slab.
 *     The existing matches are then fit to a montage patch match model.
 *     Finally, montage patch matches for the pair are derived by applying the model to each SFOV tile's
 *     corners and the matches are stored with a specified weight (typically reduced to something
 *     like 0.1 to ensure that standard matches are given precedence in future solves).
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
                multiProject.buildListOfStackMFOVWithAllZ(patch.multiFieldOfViewId);

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

    public void deriveAndSaveMatchesForUnconnectedPairsInStack(final RenderDataClient defaultDataClient,
                                                               final StackMFOVWithZValues stackMFOVWithZValues,
                                                               final MatchCollectionId matchCollectionId,
                                                               final String matchStorageCollectionName)
            throws IOException, IllegalStateException {

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
            updatePositionPairDataForZ(stack, z, sectionIds, positionToPairs, renderDataClient, matchClient);
        }

        final int totalNumberOfPositions = positionToPairs.size();
        final Set<MFOVPositionPair> positionsWithoutAnyUnconnectedPairs = new HashSet<>();
        for (final MFOVPositionPair positionPair : positionToPairs.keySet()) {
            if (! positionToPairs.get(positionPair).hasUnconnectedPairs()) {
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
                                                                           patch.crossLayerDerivedMatchWeight));
        }

        if (derivedMatchesForMFOV.size() > 0) {

            LOG.info("deriveAndSaveMatchesForUnconnectedPairsInStack: saving matches for {} pairs in {}",
                     derivedMatchesForMFOV.size(), stackMFOVWithZValues);

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

        LOG.info("deriveAndSaveMatchesForUnconnectedPairsInStack: exit, stackWithZValues={}", stackMFOVWithZValues);
    }
    
    public void updatePositionPairDataForZ(final String stack,
                                           final Double z,
                                           final Set<String> sectionIds,
                                           final Map<MFOVPositionPair, MFOVPositionPairMatchData> positionToPairs,
                                           final RenderDataClient renderDataClient,
                                           final RenderDataClient matchClient)
            throws IOException {

        LOG.info("updatePositionPairDataForZ: entry, stack={}, z={}, sectionIds={}, pTileIdPrefixForRun={}, qTileIdPrefixForRun={}",
                 stack, z, sectionIds, patch.pTileIdPrefixForRun, patch.qTileIdPrefixForRun);

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
                                                                                    false);

        // add all MFOV tile pairs to unconnected set
        final Set<OrderedCanvasIdPair> unconnectedPairsForMFOV = new HashSet<>(potentialPairsForZ.size());
        for (final OrderedCanvasIdPair pair : potentialPairsForZ) {
            if (pair.getP().getId().startsWith(patch.pTileIdPrefixForRun) &&
                pair.getQ().getId().startsWith(patch.qTileIdPrefixForRun)) {
                // remove relative position info from tree search to simplify existence check later
                final OrderedCanvasIdPair pairWithoutRelative =
                        new OrderedCanvasIdPair(pair.getP().withoutRelativePosition(),
                                                pair.getQ().withoutRelativePosition(),
                                                0.0);
                final MFOVPositionPair positionPair = new MFOVPositionPair(pairWithoutRelative);
                final MFOVPositionPairMatchData positionPairMatchData =
                        positionToPairs.computeIfAbsent(positionPair,
                                                        d -> new MFOVPositionPairMatchData(positionPair));
                positionPairMatchData.addPair(pairWithoutRelative,
                                              resolvedTiles.getTileSpec(pair.getP().getId()),
                                              resolvedTiles.getTileSpec(pair.getQ().getId()));
                unconnectedPairsForMFOV.add(pairWithoutRelative);
            }
        }

        LOG.info("updatePositionPairDataForZ: found {} pairs within mFOV {} in z {}",
                 unconnectedPairsForMFOV.size(), patch.multiFieldOfViewId, z);

        // query web service to find connected tile pairs and remove them from unconnected set
        final Map<String, OrderedCanvasIdPair> sameLayerPairsFromOtherMFOVs = new HashMap<>();
        if (unconnectedPairsForMFOV.size() > 0) {


            for (final String groupId : sectionIds) {
               for (final CanvasMatches canvasMatches : matchClient.getMatchesWithinGroup(groupId,
                                                                                          true)) {
                   final String pId = canvasMatches.getpId();
                   final String qId = canvasMatches.getqId();
                   final OrderedCanvasIdPair pair = new OrderedCanvasIdPair(new CanvasId(groupId, pId),
                                                                            new CanvasId(groupId, qId),
                                                                            0.0);
                   if (pId.startsWith(patch.pTileIdPrefixForRun) &&
                       qId.startsWith(patch.qTileIdPrefixForRun)) {
                       if (! unconnectedPairsForMFOV.remove(pair)) {
                           LOG.warn("updatePositionPairDataForZ: failed to locate existing pair {} in potential set",
                                    pair);
                       }
                   } else {
                       // TODO: as coded here, same layer pair will often come from last MFOV (19) - does it matter?
                       final String sfovIndexPairName = Utilities.getSFOVIndexPairName(canvasMatches.getpGroupId(),
                                                                                       pId,
                                                                                       qId);
                       sameLayerPairsFromOtherMFOVs.put(sfovIndexPairName, pair);
                   }
               }
            }
        }

        for (final OrderedCanvasIdPair unconnectedPair : unconnectedPairsForMFOV) {

            final MFOVPositionPair positionPair = new MFOVPositionPair(unconnectedPair);
            final MFOVPositionPairMatchData positionPairMatchData = positionToPairs.get(positionPair);
            positionPairMatchData.addUnconnectedPair(unconnectedPair);

            // add same layer pair from another MFOV if any exists
            final CanvasId p = unconnectedPair.getP();
            final String indexPairName = Utilities.getSFOVIndexPairName(p.getGroupId(),
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

        LOG.info("updatePositionPairDataForZ: exit, found {} unconnected tile pairs within mFOV {} in z {}",
                 unconnectedPairsForMFOV.size(), patch.multiFieldOfViewId, z);
    }

    private static final Logger LOG = LoggerFactory.getLogger(MFOVMontageMatchPatchClient.class);
}

package org.janelia.render.client.multisem;

import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileBoundsRTree;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MFOVMontageMatchPatchParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for patching matches missing from adjacent MFOV-as-tile pairs within the same z layer.
 * <br/><br/>
 * The client finds adjacent MFOV-as-tile pairs within the same z layer that remain unconnected
 * after standard matching.
 * Point matches are set at the corners of the area where the two MFOV-as-tiles overlap in the acquisition stack.
 */
public class MFOVAsTileMontageMatchPatchClient {

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

                final MFOVAsTileMontageMatchPatchClient client = new MFOVAsTileMontageMatchPatchClient(parameters);
                client.deriveAndSaveMatchesForAllUnconnectedPairs();
            }
        };
        clientRunner.run();
    }

    private final MultiProjectParameters multiProject;
    private final MFOVMontageMatchPatchParameters patch;

    public MFOVAsTileMontageMatchPatchClient(final Parameters parameters) {
        this.multiProject = parameters.multiProject;
        this.patch = parameters.patch;
    }

    public void deriveAndSaveMatchesForAllUnconnectedPairs()
            throws Exception {

        final RenderDataClient defaultDataClient = multiProject.getDataClient();
        final List<StackWithZValues> stackWithZValuesList = multiProject.buildListOfStackWithAllZ();

        for (final StackWithZValues stackWithZValues : stackWithZValuesList) {
            final MatchCollectionId matchCollectionId = multiProject.getMatchCollectionIdForStack(stackWithZValues.getStackId());
            deriveAndSaveMatchesForUnconnectedPairsInStack(defaultDataClient,
                                                           stackWithZValues,
                                                           matchCollectionId);
        }
    }

    /**
     * Derives and saves matches for all unconnected pairs in the specified stack.
     *
     * @return the number of tile pairs that had derived matches saved.
     */
    public int deriveAndSaveMatchesForUnconnectedPairsInStack(final RenderDataClient defaultDataClient,
                                                              final StackWithZValues stackWithZValues,
                                                              final MatchCollectionId matchCollectionId)
            throws IOException, IllegalStateException {

        LOG.info("deriveAndSaveMatchesForUnconnectedPairsInStack: entry, stackWithZValues={}", stackWithZValues);

        if (patch.startPositionMatchWeight == null) {
            throw new IllegalStateException("startPositionMatchWeight must be specified");
        }

        final StackId stackId = stackWithZValues.getStackId();
        final String stack = stackId.getStack();

        final RenderDataClient renderDataClient = defaultDataClient.buildClient(stackId.getOwner(),
                                                                                stackId.getProject());
        final RenderDataClient matchClient = renderDataClient.buildClient(matchCollectionId.getOwner(),
                                                                          matchCollectionId.getName());

        int numberOfDerivedMatchPairs = 0;
        for (final Double z :stackWithZValues.getzValues()) {
            numberOfDerivedMatchPairs += patchUnconnectedPairsForZ(stack,
                                                                   z,
                                                                   renderDataClient,
                                                                   matchClient,
                                                                   patch.xyNeighborFactor,
                                                                   patch.startPositionMatchWeight);
        }

        LOG.info("deriveAndSaveMatchesForUnconnectedPairsInStack: exit, returning {} for {}",
                 numberOfDerivedMatchPairs, stackWithZValues);

        return numberOfDerivedMatchPairs;
    }

    private static int patchUnconnectedPairsForZ(final String stack,
                                                 final Double z,
                                                 final RenderDataClient renderDataClient,
                                                 final RenderDataClient matchClient,
                                                 final Double xyNeighborFactor,
                                                 final Double startPositionMatchWeight)
            throws IOException {

        LOG.info("patchUnconnectedPairsForZ: entry, stack={}, z={}", stack, z);

        final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(stack, z);

        final List<TileBounds> tileBoundsList =
                resolvedTiles.getTileSpecs().stream().map(TileSpec::toTileBounds).collect(Collectors.toList());
        final TileBoundsRTree tree = new TileBoundsRTree(z, tileBoundsList);

        final Set<OrderedCanvasIdPair> potentialPairsForZ = tree.getCircleNeighbors(tileBoundsList,
                                                                                    new ArrayList<>(),
                                                                                    xyNeighborFactor,
                                                                                    null,
                                                                                    false,
                                                                                    false,
                                                                                    false,
                                                                                    false);

        // add all tile pairs to unconnected set to start
        final Set<OrderedCanvasIdPair> unconnectedPairs = new HashSet<>(potentialPairsForZ.size());
        for (final OrderedCanvasIdPair pair : potentialPairsForZ) {
            final CanvasId p = pair.getP();
            final CanvasId q = pair.getQ();
            // remove relative position info from tree search to simplify existence check later
            final OrderedCanvasIdPair pairWithoutRelative =
                    new OrderedCanvasIdPair(p.withoutRelativePosition(),
                                            q.withoutRelativePosition(),
                                            0.0);
            unconnectedPairs.add(pairWithoutRelative);
        }

        LOG.info("patchUnconnectedPairsForZ: found {} tiles and {} pairs in {} z {}",
                 tileBoundsList.size(), unconnectedPairs.size(), stack, z);

        // query web service to find connected tile pairs and remove them from unconnected set
        int numberOfConnectedPairsForMFOV = 0;
        if (! unconnectedPairs.isEmpty()) {
            final String groupId = String.valueOf(z);
            for (final CanvasMatches canvasMatches : matchClient.getMatchesWithinGroup(groupId,
                                                                                       true)) {
                final String pId = canvasMatches.getpId();
                final String qId = canvasMatches.getqId();
                final OrderedCanvasIdPair pair = new OrderedCanvasIdPair(new CanvasId(groupId, pId),
                                                                         new CanvasId(groupId, qId),
                                                                         0.0);
                if (unconnectedPairs.remove(pair)) {
                    numberOfConnectedPairsForMFOV++;
                } else {
                    LOG.warn("patchUnconnectedPairsForZ: failed to locate existing pair {} in potential set",
                             pair);
                }
            }
        }

        LOG.info("patchUnconnectedPairsForZ: found {} connected pairs and {} unconnected pairs within {} z {}",
                 numberOfConnectedPairsForMFOV, unconnectedPairs.size(), stack, z);

        int numberOfDerivedMatchPairs = 0;
        if (! unconnectedPairs.isEmpty()) {

            final List<CanvasMatches> derivedMatches =
                    UnconnectedMontageMFOVClient.deriveMatchesUsingStartPositions(new ArrayList<>(unconnectedPairs),
                                                                                  renderDataClient,
                                                                                  stack,
                                                                                  z,
                                                                                  startPositionMatchWeight);

            // need to check that there are matches to save because some start positions may not overlap -
            // so it is possible that the derivedMatches list is empty
            if (! derivedMatches.isEmpty()) {
                numberOfDerivedMatchPairs = derivedMatches.size();
                matchClient.saveMatches(derivedMatches);
            }

        } else {
            LOG.info("patchUnconnectedPairsForZ: no unconnected pairs found in {} z {}", stack, z);
        }

        return numberOfDerivedMatchPairs;
    }

    private static final Logger LOG = LoggerFactory.getLogger(MFOVAsTileMontageMatchPatchClient.class);
}

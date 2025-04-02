package org.janelia.render.client.multisem;

import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.parameters.TilePairDerivationParameters;
import org.janelia.alignment.multisem.LayerMFOV;
import org.janelia.alignment.multisem.MultiSemUtilities;
import org.janelia.alignment.multisem.OrderedMFOVPair;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.TilePairClient;
import org.janelia.render.client.match.InMemoryTilePairClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for finding adjacent MFOVs in the same z layer that are unconnected.
 */
public class UnconnectedMontageMFOVClient {

    public static class Parameters
            extends CommandLineParameters {

        @ParametersDelegate
        public MultiProjectParameters multiProject = new MultiProjectParameters();

    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args)
                    throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final UnconnectedMontageMFOVClient client = new UnconnectedMontageMFOVClient(parameters);
                client.findUnconnectedMFOVs();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;


    public UnconnectedMontageMFOVClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    public void findUnconnectedMFOVs()
            throws IOException {

        final RenderDataClient renderDataClient = parameters.multiProject.getDataClient();
        final List<StackWithZValues> stackWithZList = parameters.multiProject.buildListOfStackWithAllZ();

        for (final StackWithZValues stackWithZ : stackWithZList) {
            findUnconnectedPairsBetweenMFOVsInStack(stackWithZ,
                                                    parameters.multiProject.deriveMatchCollectionNamesFromProject,
                                                    renderDataClient);
        }
    }

    public void findUnconnectedPairsBetweenMFOVsInStack(final StackWithZValues stackWithZ,
                                                        final boolean deriveMatchCollectionNamesFromProject,
                                                        final RenderDataClient renderDataClient)
            throws IOException {

        LOG.info("findUnconnectedPairsBetweenMFOVsInStack: entry, stackWithZ={}", stackWithZ);

        final StackId renderStackId = stackWithZ.getStackId();
        final MatchCollectionId matchCollectionId =
                renderStackId.getDefaultMatchCollectionId(deriveMatchCollectionNamesFromProject);
        final RenderDataClient matchClient = renderDataClient.buildClient(matchCollectionId.getOwner(),
                                                                          matchCollectionId.getName());

        int problemPairsCount = 0;
        for (final StackWithZValues stackWithSingleZ : stackWithZ.splitByZ()) {
            problemPairsCount +=
                    findUnconnectedPairsAcrossMFOVsInOneZLayer(renderDataClient, stackWithSingleZ, matchClient);
        }

        if (problemPairsCount == 0) {
            LOG.info("findUnconnectedPairsBetweenMFOVsInStack: exit, stackWithZ={}, all MFOVs are all connected",
                     stackWithZ);
        } else {
            LOG.warn("findUnconnectedPairsBetweenMFOVsInStack: exit, stackWithZ={}, found {} unconnected pairs between MFOVs",
                     stackWithZ, problemPairsCount);
        }
    }

    public static int findUnconnectedPairsAcrossMFOVsInOneZLayer(final RenderDataClient renderDataClient,
                                                                 final StackWithZValues stackWithSingleZ,
                                                                 final RenderDataClient matchClient)
            throws IOException {

        final List<OrderedCanvasIdPair> potentialDifferentMfovPairs =
                findPotentialSameLayerPairsWithDifferentMfovs(renderDataClient.getBaseDataUrl(),
                                                              stackWithSingleZ);

        final Double z = stackWithSingleZ.getFirstZ();
        final String groupId = String.valueOf(z);
        final Set<OrderedCanvasIdPair> existingSameLayerPairs =
                matchClient.getMatchesWithinGroup(groupId, true).stream()
                        .map(CanvasMatches::toOrderedPair).collect(Collectors.toSet());

        final Map<String, Set<String>> tileIdToConnectedMfovsMap = new HashMap<>();
        final Set<String> internallyConnectedMFOVs = new HashSet<>();
        final Set<OrderedMFOVPair> connectedMFOVPairs = new HashSet<>();
        for (final OrderedCanvasIdPair pair : existingSameLayerPairs) {
            final String pTileId = pair.getP().getId();
            final String pMfovId = MultiSemUtilities.getMagcMfovForTileId(pTileId);
            final String qTileId = pair.getQ().getId();
            final String qMfovId = MultiSemUtilities.getMagcMfovForTileId(qTileId);
            tileIdToConnectedMfovsMap.computeIfAbsent(pTileId, k -> new HashSet<>()).add(qMfovId);
            tileIdToConnectedMfovsMap.computeIfAbsent(qTileId, k -> new HashSet<>()).add(pMfovId);
            if (pMfovId.equals(qMfovId)) {
                internallyConnectedMFOVs.add(pMfovId);
            } else {
                connectedMFOVPairs.add(new OrderedMFOVPair(new LayerMFOV(z, pMfovId),
                                                           new LayerMFOV(z, qMfovId)));
            }
        }

        final List<OrderedCanvasIdPair> problemPairs = new ArrayList<>();
        for (final OrderedCanvasIdPair pair : potentialDifferentMfovPairs) {

            if (existingSameLayerPairs.contains(pair)) {
               continue; // matches already exist between p and q, move on
            }

            // matches do not exist between p and q
            final String pTileId = pair.getP().getId();
            final String qTileId = pair.getQ().getId();

            // if other matches do exist for both p and q ...
            if (tileIdToConnectedMfovsMap.containsKey(pTileId) && tileIdToConnectedMfovsMap.containsKey(qTileId)) {

                // they are not resin, so see if any other pairs between the same two MFOVs exist ...
                final String pMfovId = MultiSemUtilities.getMagcMfovForTileId(pTileId);
                final LayerMFOV pLayerMfov = new LayerMFOV(z, pMfovId);
                final String qMfovId = MultiSemUtilities.getMagcMfovForTileId(qTileId);
                final LayerMFOV qLayerMfov = new LayerMFOV(z, qMfovId);

                final OrderedMFOVPair crossMfovPair = new OrderedMFOVPair(pLayerMfov, qLayerMfov);

                if (connectedMFOVPairs.contains(crossMfovPair)) {
                    // p is connected to q's mfov through some other tile,
                    // so there is no need to move p since rough alignment will get things positioned correctly
                    continue;
                }

                // if both p and q are connected to other tiles in their own mfov, but not to each other's mfov ...
                if (internallyConnectedMFOVs.contains(pMfovId) && internallyConnectedMFOVs.contains(qMfovId)) {
                    problemPairs.add(pair); // flag the pair as a problem
                }
                // otherwise p and or q are not connected to any tiles in their own mfov so they are likely resin
            }
        }

        final String problemPairsString =
                problemPairs.size() < 5 ? String.valueOf(problemPairs) : String.valueOf(problemPairs.subList(0, 5));

        if (problemPairs.isEmpty()) {
            LOG.info("findUnconnectedPairsAcrossMFOVsInOneZLayer: z {} MFOVs are all connected",
                     stackWithSingleZ.getFirstZ());
        } else {
            LOG.warn("findUnconnectedPairsAcrossMFOVsInOneZLayer: z {} has {} unconnected pairs with different MFOVs like {}",
                     stackWithSingleZ.getFirstZ(), problemPairs.size(), problemPairsString);
        }

        return problemPairs.size();
    }

    public static List<OrderedCanvasIdPair> findPotentialSameLayerPairsWithDifferentMfovs(final String baseDataUrl,
                                                                                          final StackWithZValues stackWithZ)
            throws IOException {

        final TilePairDerivationParameters tpdp = new TilePairDerivationParameters();

        tpdp.xyNeighborFactor = 0.6;
        tpdp.zNeighborDistance = 0;
        tpdp.excludeSameMfovNeighbors = true;

        tpdp.excludeCompletelyObscuredTiles = false;
        tpdp.excludeCornerNeighbors = false;
        tpdp.excludeSameLayerNeighbors = false;
        tpdp.excludeSameSectionNeighbors = false;
        tpdp.minExistingMatchCount = 0;
        tpdp.useRowColPositions = false;

        final TilePairClient.Parameters tilePairParameters =
                new TilePairClient.Parameters(baseDataUrl,
                                              stackWithZ,
                                              tpdp,
                                              "/tmp/tile_pairs.json"); // ignored by in-memory client;

        final InMemoryTilePairClient tilePairClient = new InMemoryTilePairClient(tilePairParameters);
        tilePairClient.deriveAndSaveSortedNeighborPairs();

        // remove relative position data so that these pairs can be compared to database pairs
        return tilePairClient.getNeighborPairs().stream()
                .map(pair ->
                             new OrderedCanvasIdPair(pair.getP().withoutRelativePosition(),
                                                     pair.getQ().withoutRelativePosition(),
                                                     pair.getAbsoluteDeltaZ()))
                .collect(Collectors.toList());
    }

    private static final Logger LOG = LoggerFactory.getLogger(UnconnectedMontageMFOVClient.class);
}

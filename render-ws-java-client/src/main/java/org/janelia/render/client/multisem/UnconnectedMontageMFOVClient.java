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
 * Java client for finding adjacent MFOVs in the same z layer that contain tissue but are unconnected.
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
                final List<LayerMFOV> isolatedMFOVs = client.findIsolatedTissueMFOVs();

                LOG.info("runClient: exit, found {} isolatedMFOVs {}", isolatedMFOVs.size(), isolatedMFOVs);
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;


    public UnconnectedMontageMFOVClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    public List<LayerMFOV> findIsolatedTissueMFOVs()
            throws IOException {

        final RenderDataClient renderDataClient = parameters.multiProject.getDataClient();
        final List<StackWithZValues> stackWithZList = parameters.multiProject.buildListOfStackWithAllZ();

        final List<LayerMFOV> isolatedMFOVs = new ArrayList<>();
        for (final StackWithZValues stackWithZ : stackWithZList) {
            isolatedMFOVs.addAll(
                    findIsolatedTissueMFOVsInStack(stackWithZ,
                                                   parameters.multiProject.deriveMatchCollectionNamesFromProject,
                                                   renderDataClient));
        }

        LOG.info("findIsolatedTissueMFOVs: returning {} isolated MFOV(s)", isolatedMFOVs.size());

        return isolatedMFOVs;
    }

    public List<LayerMFOV> findIsolatedTissueMFOVsInStack(final StackWithZValues stackWithZ,
                                                          final boolean deriveMatchCollectionNamesFromProject,
                                                          final RenderDataClient renderDataClient)
            throws IOException {

        LOG.info("findIsolatedTissueMFOVsInStack: entry, {}", stackWithZ);

        final StackId renderStackId = stackWithZ.getStackId();
        final MatchCollectionId matchCollectionId =
                renderStackId.getDefaultMatchCollectionId(deriveMatchCollectionNamesFromProject);
        final RenderDataClient matchClient = renderDataClient.buildClient(matchCollectionId.getOwner(),
                                                                          matchCollectionId.getName());

        final List<LayerMFOV> isolatedMFOVsForStack = new ArrayList<>();
        for (final StackWithZValues stackWithSingleZ : stackWithZ.splitByZ()) {
            isolatedMFOVsForStack.addAll(
                    findIsolatedTissueMFOVsInOneZLayer(renderDataClient, stackWithSingleZ, matchClient));
        }

        LOG.info("findIsolatedTissueMFOVsInStack: {} has {} isolated MFOV(s)",
                 stackWithZ, isolatedMFOVsForStack.size());

        return isolatedMFOVsForStack;
    }

    public static List<LayerMFOV> findIsolatedTissueMFOVsInOneZLayer(final RenderDataClient renderDataClient,
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
        final Set<String> externallyConnectedMFOVs = new HashSet<>();
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
                externallyConnectedMFOVs.add(pMfovId);
                externallyConnectedMFOVs.add(qMfovId);
            }
        }

        final Set<String> isolatedMFOVs = new HashSet<>();
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

                // they are not resin, so see if either of the MFOVs are isolated ...
                final String pMfovId = MultiSemUtilities.getMagcMfovForTileId(pTileId);
                final String qMfovId = MultiSemUtilities.getMagcMfovForTileId(qTileId);

                final boolean isPMfovIsolated = internallyConnectedMFOVs.contains(pMfovId) && (! externallyConnectedMFOVs.contains(pMfovId));
                final boolean isQMfovIsolated = internallyConnectedMFOVs.contains(qMfovId) && (! externallyConnectedMFOVs.contains(qMfovId));

                if (isPMfovIsolated) {

                    isolatedMFOVs.add(pMfovId);
                    problemPairs.add(pair);

                    if (isQMfovIsolated) {
                        isolatedMFOVs.add(qMfovId);
                    }

                } else if (isQMfovIsolated) {

                    isolatedMFOVs.add(qMfovId);
                    problemPairs.add(pair);

                }

            }
        }

        if (! problemPairs.isEmpty()) {
            final String problemDetails = problemPairs.size() < 5 ?
                                          String.valueOf(problemPairs) : String.valueOf(problemPairs.subList(0, 5));
            LOG.info("findIsolatedTissueMFOVsInOneZLayer: {} has {} problem tile pairs like {}",
                     stackWithSingleZ, problemPairs.size(), problemDetails);
        }

        final List<LayerMFOV> sortedIsolatedMFOVs = isolatedMFOVs.stream()
                .sorted()
                .map(mfov_name -> new LayerMFOV(z, mfov_name))
                .collect(Collectors.toList());

        LOG.info("findIsolatedTissueMFOVsInOneZLayer: {} has {} isolated MFOV(s) {}",
                 stackWithSingleZ, sortedIsolatedMFOVs.size(), sortedIsolatedMFOVs);

        return sortedIsolatedMFOVs;
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

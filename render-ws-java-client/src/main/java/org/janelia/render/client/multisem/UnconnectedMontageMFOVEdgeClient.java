package org.janelia.render.client.multisem;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.awt.Rectangle;
import java.io.IOException;
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
import org.janelia.alignment.match.parameters.TilePairDerivationParameters;
import org.janelia.alignment.match.stage.StageMatcher;
import org.janelia.alignment.multisem.LayerMFOV;
import org.janelia.alignment.multisem.MultiSemUtilities;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
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
 * Java client for finding adjacent MFOVs in the same z layer that have connected tiles
 * along their edge, but are not connected to each other.
 * Results are logged.  If the --addIsolatedEdgeLabel option is specified,
 * the label 'isolated_edge' is added to all tiles in MFOVs with isolated edges.
 */
public class UnconnectedMontageMFOVEdgeClient {

    public static class Parameters
            extends CommandLineParameters {

        @ParametersDelegate
        public MultiProjectParameters multiProject = new MultiProjectParameters();

        @Parameter(
                names = "--addIsolatedEdgeLabel",
                description = "Specify to add the label 'isolated_edge' to all SFOVs in MFOVs with isolated edges",
                arity = 0)
        public boolean addIsolatedEdgeLabel = false;

        @Parameter(
                names = "--startPositionMatchWeight",
                description = "Weight (e.g. 0.001) for matches derived from SFOV start positions.  " +
                              "Specify to patch all isolated edge pairs with positions based upon SFOV stage locations.  " +
                              "Omit to skip start position derivation.")
        public Double startPositionMatchWeight;

    }

    /** Label for tiles in MFOVs with isolated edges. */
    public static String ISOLATED_EDGE_LABEL = "isolated_edge";

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args)
                    throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final UnconnectedMontageMFOVEdgeClient client = new UnconnectedMontageMFOVEdgeClient(parameters);
                final List<LayerMFOV> isolatedMFOVs = client.findIsolatedEdgeMFOVs();

                LOG.info("runClient: exit, found {} isolatedMFOVs {}", isolatedMFOVs.size(), isolatedMFOVs);
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;


    public UnconnectedMontageMFOVEdgeClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    public List<LayerMFOV> findIsolatedEdgeMFOVs()
            throws IOException {

        final RenderDataClient renderDataClient = parameters.multiProject.getDataClient();
        final List<StackWithZValues> stackWithZList = parameters.multiProject.buildListOfStackWithAllZ();

        final List<LayerMFOV> isolatedMFOVs = new ArrayList<>();
        for (final StackWithZValues stackWithZ : stackWithZList) {
            isolatedMFOVs.addAll(
                    findIsolatedEdgeMFOVsInStack(stackWithZ,
                                                 parameters.multiProject.deriveMatchCollectionNamesFromProject,
                                                 renderDataClient,
                                                 parameters.addIsolatedEdgeLabel,
                                                 parameters.startPositionMatchWeight));
        }

        LOG.info("findIsolatedEdgeMFOVs: returning {} isolated MFOV(s)", isolatedMFOVs.size());

        return isolatedMFOVs;
    }

    public static List<LayerMFOV> findIsolatedEdgeMFOVsInStack(final StackWithZValues stackWithZ,
                                                               final boolean deriveMatchCollectionNamesFromProject,
                                                               final RenderDataClient renderDataClient,
                                                               final boolean addIsolatedEdgeLabel,
                                                               final Double startPositionMatchWeight)
            throws IOException {

        LOG.info("findIsolatedEdgeMFOVsInStack: entry, {}", stackWithZ);

        final StackId renderStackId = stackWithZ.getStackId();
        final MatchCollectionId matchCollectionId =
                renderStackId.getDefaultMatchCollectionId(deriveMatchCollectionNamesFromProject);
        final RenderDataClient matchClient = renderDataClient.buildClient(matchCollectionId.getOwner(),
                                                                          matchCollectionId.getName());

        final List<LayerMFOV> isolatedMFOVsForStack = new ArrayList<>();
        for (final StackWithZValues stackWithSingleZ : stackWithZ.splitByZ()) {
            isolatedMFOVsForStack.addAll(
                    findIsolatedEdgeMFOVsInOneZLayer(renderDataClient,
                                                     stackWithSingleZ,
                                                     matchClient,
                                                     startPositionMatchWeight));
        }

        LOG.info("findIsolatedEdgeMFOVsInStack: {} has {} isolated MFOV(s)",
                 stackWithZ, isolatedMFOVsForStack.size());

        if (addIsolatedEdgeLabel) {
            addIsolatedEdgeLabelToTiles(isolatedMFOVsForStack,
                                        renderStackId.getStack(),
                                        renderDataClient);
        }

        return isolatedMFOVsForStack;
    }

    public static List<LayerMFOV> findIsolatedEdgeMFOVsInOneZLayer(final RenderDataClient renderDataClient,
                                                                   final StackWithZValues stackWithSingleZ,
                                                                   final RenderDataClient matchClient,
                                                                   final Double startPositionMatchWeight)
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
        final Set<String> internalEdgeConnectedTileIds = new HashSet<>();
        final Set<String> externallyConnectedMFOVs = new HashSet<>();
        for (final OrderedCanvasIdPair pair : existingSameLayerPairs) {
            final String pTileId = pair.getP().getId();
            final String pMfovId = MultiSemUtilities.getMagcMfovForTileId(pTileId);
            final String qTileId = pair.getQ().getId();
            final String qMfovId = MultiSemUtilities.getMagcMfovForTileId(qTileId);
            tileIdToConnectedMfovsMap.computeIfAbsent(pTileId, k -> new HashSet<>()).add(qMfovId);
            tileIdToConnectedMfovsMap.computeIfAbsent(qTileId, k -> new HashSet<>()).add(pMfovId);
            if (pMfovId.equals(qMfovId)) {
                // MFOVs are the same, so check if the pair is along the edge of the MFOV
                final int pSfovIndex = Integer.parseInt(MultiSemUtilities.getSFOVIndexForTileId(pTileId));
                final int qSfovIndex = Integer.parseInt(MultiSemUtilities.getSFOVIndexForTileId(qTileId));
                if ((pSfovIndex > 61) && (qSfovIndex > 61)) {
                    internalEdgeConnectedTileIds.add(pTileId);
                    internalEdgeConnectedTileIds.add(qTileId);
                }
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

                final boolean isPMfovIsolated = internalEdgeConnectedTileIds.contains(pTileId) &&
                                                (! externallyConnectedMFOVs.contains(pMfovId));
                final boolean isQMfovIsolated = internalEdgeConnectedTileIds.contains(qTileId) &&
                                                (! externallyConnectedMFOVs.contains(qMfovId));

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

            LOG.info("findIsolatedEdgeMFOVsInOneZLayer: {} has {} problem tile pairs like {}",
                     stackWithSingleZ, problemPairs.size(), problemDetails);

            if (startPositionMatchWeight != null) {

                final List<CanvasMatches> derivedMatches =
                        deriveMatchesUsingStartPositions(problemPairs,
                                                         renderDataClient,
                                                         stackWithSingleZ.getStackId().getStack(),
                                                         z,
                                                         startPositionMatchWeight);

                // need to check that there are matches to save because some start positions may not overlap -
                // so it is possible that the derivedMatches list is empty
                if (! derivedMatches.isEmpty()) {
                    matchClient.saveMatches(derivedMatches);
                }
            }
        }

        final List<LayerMFOV> sortedIsolatedMFOVs = isolatedMFOVs.stream()
                .sorted()
                .map(mfov_name -> new LayerMFOV(z, mfov_name))
                .collect(Collectors.toList());

        LOG.info("findIsolatedEdgeMFOVsInOneZLayer: {} has {} isolated MFOV(s) {}",
                 stackWithSingleZ, sortedIsolatedMFOVs.size(), sortedIsolatedMFOVs);

        return sortedIsolatedMFOVs;
    }

    public static List<CanvasMatches> deriveMatchesUsingStartPositions(final List<OrderedCanvasIdPair> unconnectedPairs,
                                                                       final RenderDataClient renderDataClient,
                                                                       final String stackName,
                                                                       final double z,
                                                                       final double startPositionMatchWeight)
            throws IOException {

        LOG.info("deriveMatchesUsingStartPositions: entry, {} unconnected pairs for z {} of stack {}",
                 unconnectedPairs.size(), z, stackName);

        final List<CanvasMatches> derivedMatchesList = new ArrayList<>();

        final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(stackName, z);

        for (final OrderedCanvasIdPair pair : unconnectedPairs) {
            final CanvasId p = pair.getP();
            final TileSpec pTileSpec = resolvedTiles.getTileSpec(p.getId());
            final Rectangle pWorldBounds = pTileSpec.toTileBounds().toRectangle();

            final CanvasId q = pair.getQ();
            final TileSpec qTileSpec = resolvedTiles.getTileSpec(q.getId());
            final Rectangle qWorldBounds = qTileSpec.toTileBounds().toRectangle();

            final CanvasMatches startPositionMatches =
                    StageMatcher.generateStartPositionOverlapMatches(p,
                                                                     pWorldBounds,
                                                                     q,
                                                                     qWorldBounds,
                                                                     startPositionMatchWeight);

            // need to check that the start position matches exist - they will be null if the two tiles do not overlap
            if (startPositionMatches != null) {
                derivedMatchesList.add(startPositionMatches);
            }
        }

        return derivedMatchesList;
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

    public static void addIsolatedEdgeLabelToTiles(final List<LayerMFOV> isolatedMFOVs,
                                                   final String renderStack,
                                                   final RenderDataClient renderDataClient)
            throws IOException {

        LOG.info("addIsolatedEdgeLabelToTiles: entry, with {} isolatedMFOVs for stack {}",
                 isolatedMFOVs.size(), renderStack);

        if (! isolatedMFOVs.isEmpty()) {


            renderDataClient.ensureStackIsInLoadingState(renderStack, null);

            final Map<Double, Set<String>> zToMFOVSet = LayerMFOV.buildZToMFOVNamesMap(isolatedMFOVs);

            for (final Double z : zToMFOVSet.keySet().stream().sorted().collect(Collectors.toList())) {
                final Set<String> isolatedLayerMFOVNames = zToMFOVSet.get(z);

                final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(renderStack, z);

                final Set<String> unchangedTileIds = new HashSet<>();
                for (final TileSpec tileSpec : resolvedTiles.getTileSpecs()) {
                    final String mfovName = MultiSemUtilities.getMagcMfovForTileId(tileSpec.getTileId());
                    if (isolatedLayerMFOVNames.contains(mfovName)) {
                        tileSpec.addLabel(ISOLATED_EDGE_LABEL);
                    } else {
                        unchangedTileIds.add(tileSpec.getTileId());
                    }
                }

                // remove unchanged tiles from the collection, so we don't re-save them
                resolvedTiles.removeTileSpecs(unchangedTileIds);

                LOG.info("addIsolatedEdgeLabelToTiles: saving {} tile specs with label '{}' for z {} of stack {}",
                         resolvedTiles.getTileCount(), ISOLATED_EDGE_LABEL, z, renderStack);

                renderDataClient.saveResolvedTiles(resolvedTiles, renderStack, z);
            }

            renderDataClient.setStackState(renderStack, StackMetaData.StackState.COMPLETE);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(UnconnectedMontageMFOVEdgeClient.class);
}

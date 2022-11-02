package org.janelia.render.client.multisem;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.nio.file.Files;
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
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileBoundsRTree;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.FileUtil;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for deriving point matches for adjacent same-z-layer tile pairs using SIFT match
 * data from related tile pairs the same multi-field-of-view (mFOV) but different z-layers.
 *
 * @author Stephan Preibisch
 * @author Eric Trautman
 */
public class MFOVMatchClient {

    public static class Parameters
            extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Stack to process",
                variableArity = true,
                required = true)
        public String stack;

        @Parameter(
                names = "--mfov",
                description = "Multi-field-of-view identifier <slab number>_<mfov number> (e.g. 001_000006)")
        public String multiFieldOfViewId;

        @Parameter(
                names = "--matchOwner",
                description = "Match collection owner (default is to use stack owner)")
        public String matchOwner;

        @Parameter(
                names = "--matchCollection",
                description = "Match collection name",
                required = true)
        public String matchCollection;

        @Parameter(
                names = "--stored_match_weight",
                description = "Weight for stored matches (e.g. 0.0001)",
                required = true)
        public Double storedMatchWeight;

        @Parameter(
                names = "--xyNeighborFactor",
                description = "Multiply this by max(width, height) of each tile to determine radius for locating neighbor tiles",
                required = true
        )
        public Double xyNeighborFactor;

        @Parameter(
                names = "--pTileId",
                description = "Only derive matches for positions associated with this p tile (overrides --mfov parameter)"
        )
        public String pTileId;
        public String pTileIdPrefixForRun;

        @Parameter(
                names = "--qTileId",
                description = "Only derive matches for positions associated with this q tile (overrides --mfov parameter)"
        )
        public String qTileId;
        public String qTileIdPrefixForRun;

        @Parameter(
                names = "--matchStorageFile",
                description = "File to store matches (omit if matches should be stored through web service)"
        )
        public String matchStorageFile;

        public Parameters() {
        }

        public String getMatchOwner() {
            return matchOwner == null ? renderWeb.owner : matchOwner;
        }

        // 001_000006_019_20220407_115555.1247.0 => 001_000006
        public String getMFOVForTileId(final String tileId) throws IllegalArgumentException {
            if (tileId.length() < 10) {
                throw new IllegalArgumentException("MFOV identifier cannot be derived from tileId " + tileId);
            }
            return tileId.substring(0, 10);
        }

        // 001_000006_019_20220407_115555.1247.0 => 001_000006_019
        public String getTileIdPrefixForRun(final String tileId) throws IllegalArgumentException {
            if (tileId.length() < 14) {
                throw new IllegalArgumentException("MFOV position cannot be derived from tileId " + tileId);
            }
            return tileId.substring(0, 14);
        }

        public void validateAndSetupDerivedValues()
                throws IllegalArgumentException {

            if (pTileId != null) {
                multiFieldOfViewId = getMFOVForTileId(pTileId);
                pTileIdPrefixForRun = getTileIdPrefixForRun(pTileId);
                if (qTileId != null) {
                    if (! multiFieldOfViewId.equals(getMFOVForTileId(qTileId))) {
                        throw new IllegalArgumentException("pTileId and qTileId reference different MFOVs");
                    }
                    qTileIdPrefixForRun = getTileIdPrefixForRun(qTileId);
                } else {
                    qTileIdPrefixForRun = multiFieldOfViewId;
                }
            } else if (qTileId != null) {
                multiFieldOfViewId = getMFOVForTileId(qTileId);
                qTileIdPrefixForRun = getTileIdPrefixForRun(qTileId);
                pTileIdPrefixForRun = multiFieldOfViewId;
            } else if ((multiFieldOfViewId == null) || (multiFieldOfViewId.length() != 10)) {
                throw new IllegalArgumentException("--mfov should be a 10 character value (e.g. 001_000006)");
            } else {
                pTileIdPrefixForRun = multiFieldOfViewId;
                qTileIdPrefixForRun = multiFieldOfViewId;
            }

            if (matchStorageFile != null) {
                final Path storagePath = Paths.get(matchStorageFile).toAbsolutePath();
                if (Files.exists(storagePath)) {
                    if (! Files.isWritable(storagePath)) {
                        throw new IllegalArgumentException("not allowed to write to " + storagePath);
                    }
                } else if (! Files.isWritable(storagePath.getParent())) {
                    throw new IllegalArgumentException("not allowed to write to " + storagePath.getParent());
                }
            }
        }
    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args)
                    throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);
                parameters.validateAndSetupDerivedValues();

                LOG.info("runClient: entry, parameters={}", parameters);

                final MFOVMatchClient client = new MFOVMatchClient(parameters);
                client.deriveAndSaveMatchesForUnconnectedPairs();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final RenderDataClient renderDataClient;
    private final RenderDataClient matchClient;

    MFOVMatchClient(final Parameters parameters) {
        this.parameters = parameters;
        this.renderDataClient = parameters.renderWeb.getDataClient();
        this.matchClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                parameters.getMatchOwner(),
                                                parameters.matchCollection);
    }

    public void deriveAndSaveMatchesForUnconnectedPairs()
            throws IOException {

        LOG.info("deriveAndSaveMatchesForUnconnectedPairs: entry");

        final Map<Double, Set<String>> zToSectionIdsMap =
                renderDataClient.getStackZToSectionIdsMap(parameters.stack,
                                                          null,
                                                          null,
                                                          null);

        final Map<MFOVPositionPair, MFOVPositionPairMatchData> positionToPairs = new HashMap<>();

        for (final Double z : zToSectionIdsMap.keySet().stream().sorted().collect(Collectors.toList())) {
            final Set<String> sectionIds = zToSectionIdsMap.get(z);
            updatePositionPairDataForZ(z, sectionIds, positionToPairs);
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

        LOG.info("deriveAndSaveMatchesForUnconnectedPairs: {} out of {} positions have at least one unconnected pair",
                 positionToPairs.size(), totalNumberOfPositions);

        final List<CanvasMatches> derivedMatchesForMFOV = new ArrayList<>();

        final List<MFOVPositionPair> sortedPositions =
                positionToPairs.keySet().stream().sorted().collect(Collectors.toList());
        for (final MFOVPositionPair positionPair : sortedPositions) {
            final MFOVPositionPairMatchData positionPairMatchData = positionToPairs.get(positionPair);
            derivedMatchesForMFOV.addAll(
                    positionPairMatchData.deriveMatchesForUnconnectedPairs(matchClient,
                                                                           parameters.storedMatchWeight));
        }

        if (derivedMatchesForMFOV.size() > 0) {

            LOG.info("deriveAndSaveMatchesForUnconnectedPairs: saving matches for {} pairs", derivedMatchesForMFOV.size());

            if (parameters.matchStorageFile != null) {
                final Path storagePath = Paths.get(parameters.matchStorageFile).toAbsolutePath();
                FileUtil.saveJsonFile(storagePath.toString(), derivedMatchesForMFOV);
            } else {
                // TODO: uncomment save when we are ready
                // matchClient.saveMatches(derivedMatchesForMFOV);
            }

        } else {
            LOG.info("deriveAndSaveMatchesForUnconnectedPairs: no pairs have matches so there is nothing to save");
        }

        LOG.info("deriveAndSaveMatchesForUnconnectedPairs: exit");
    }
    
    public void updatePositionPairDataForZ(final Double z,
                                           final Set<String> sectionIds,
                                           final Map<MFOVPositionPair, MFOVPositionPairMatchData> positionToPairs)
            throws IOException {

        LOG.info("updatePositionPairDataForZ: entry, z={}", z);

        final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(parameters.stack, z);

        final List<TileBounds> tileBoundsList =
                resolvedTiles.getTileSpecs().stream().map(TileSpec::toTileBounds).collect(Collectors.toList());
        final TileBoundsRTree tree = new TileBoundsRTree(z, tileBoundsList);

        final Set<OrderedCanvasIdPair> potentialPairsForZ = tree.getCircleNeighbors(tileBoundsList,
                                                                                    new ArrayList<>(),
                                                                                    parameters.xyNeighborFactor,
                                                                                    null,
                                                                                    false,
                                                                                    false,
                                                                                    false);

        // add all MFOV tile pairs to unconnected set
        final Set<OrderedCanvasIdPair> unconnectedPairsForMFOV = new HashSet<>(potentialPairsForZ.size());
        for (final OrderedCanvasIdPair pair : potentialPairsForZ) {
            if (pair.getP().getId().startsWith(parameters.pTileIdPrefixForRun) &&
                pair.getQ().getId().startsWith(parameters.qTileIdPrefixForRun)) {
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
                 unconnectedPairsForMFOV.size(), parameters.multiFieldOfViewId, z);

        // query web service to find connected tile pairs and remove them from unconnected set
        if (unconnectedPairsForMFOV.size() > 0) {
            for (final String groupId : sectionIds) {
               for (final CanvasMatches canvasMatches : matchClient.getMatchesWithinGroup(groupId,
                                                                                          true)) {
                   final String pId = canvasMatches.getpId();
                   final String qId = canvasMatches.getqId();
                   if (pId.startsWith(parameters.pTileIdPrefixForRun) &&
                       qId.startsWith(parameters.qTileIdPrefixForRun)) {
                       final OrderedCanvasIdPair pair = new OrderedCanvasIdPair(new CanvasId(groupId, pId),
                                                                                new CanvasId(groupId, qId),
                                                                                0.0);
                       if (! unconnectedPairsForMFOV.remove(pair)) {
                           LOG.warn("updatePositionPairDataForZ: failed to locate existing pair {} in potential set",
                                    pair);
                       }
                   }
               }
            }
        }

        for (final OrderedCanvasIdPair unconnectedPair : unconnectedPairsForMFOV) {
            final MFOVPositionPair positionPair = new MFOVPositionPair(unconnectedPair);
            final MFOVPositionPairMatchData positionPairMatchData = positionToPairs.get(positionPair);
            positionPairMatchData.addUnconnectedPair(unconnectedPair);
        }

        LOG.info("updatePositionPairDataForZ: exit, found {} unconnected tile pairs within mFOV {} in z {}",
                 unconnectedPairsForMFOV.size(), parameters.multiFieldOfViewId, z);
    }

    private static final Logger LOG = LoggerFactory.getLogger(MFOVMatchClient.class);
}

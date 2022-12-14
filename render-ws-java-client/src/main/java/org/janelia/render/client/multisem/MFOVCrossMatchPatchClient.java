package org.janelia.render.client.multisem;

import com.beust.jcommander.Parameter;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import mpicbg.models.Point;
import mpicbg.models.PointMatch;

import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasMatchResult;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.janelia.alignment.match.parameters.FeatureStorageParameters;
import org.janelia.alignment.multisem.OrderedMFOVPair;
import org.janelia.alignment.multisem.UnconnectedMFOVPairsForStack;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.util.FileUtil;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.MultiStagePointMatchClient;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MatchWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.janelia.alignment.match.RenderableCanvasIdPairs.TEMPLATE_GROUP_ID_TOKEN;

/**
 * Java client for patching matches missing between the same MFOV in adjacent (cross) z layers.
 * <br/><br/>
 * The client:
 * <ul>
 *   <li>
 *       For each unconnected cross MFOV pair, renders the stitched MFOVs and generates matches between them.
 *       The hope is that enough matches can be found when comparing scaled views of entire MFOVs.
 *       Note that the rendered MFOVs should be stitched in isolation, meaning that the SFOV tiles
 *       in each MFOV contain transforms solely derived from matches with SFOV tiles in the same MFOV.
 *   </li>
 *   <li>
 *       Transforms the MFOV pair matches into SFOV tile matches for one or more SFOV tile pairs.
 *       Most (if not all) of the transformed match point locations will be outside the bounds of the SFOV tiles,
 *       but that does not matter to solvers.  It simply means that the match points may not be visible in some
 *       tile pair views since match points are typically within the bounds of the tile.
 *   </li>
 *       Stores the transformed matches with a specified weight (typically something like 0.9 to help
 *       differentiate patch matches from standard matches which have a weight of 1.0).
 *   </li>
 * </ul>
 *
 * @author Stephan Preibisch
 * @author Eric Trautman
 */
public class MFOVCrossMatchPatchClient {

    public static class Parameters
            extends CommandLineParameters {

        @Parameter(
                names = "--baseDataUrl",
                description = "Base web service URL for data (e.g. http://host[:port]/render-ws/v1)",
                required = true)
        public String baseDataUrl;

        @Parameter(
                names = "--unconnectedMFOVPairsFile",
                description = "File with unconnected MFOV pairs",
                required = true)
        public String unconnectedMFOVPairsFile;

        @Parameter(
                names = "--sfovIndex",
                description = "Zero-padded three character single-field-of-view index (000 - 091) " +
                              "for which cross patch matches should be generated.",
                variableArity = true,
                required = true
        )
        public List<String> sFOVIndexList;

        @Parameter(
                names = "--storedMatchWeight",
                description = "Weight for stored matches (e.g. 0.0001)",
                required = true)
        public Double storedMatchWeight;

        @Parameter(
                names = "--stageJson",
                description = "JSON file where stage match parameters are defined",
                required = true)
        public String stageJson;

        @Parameter(
                names = "--matchStorageFile",
                description = "File to store matches (omit if matches should be stored through web service)"
        )
        public String matchStorageFile;

        private Set<String> sFOVIndexSet;

        public Parameters() {
        }

        public Path getStoragePath(final Double pZ,
                                   final Double qZ,
                                   final String mFOVName) {
            final Path storagePath;
            if (matchStorageFile == null) {
                storagePath = null;
            } else {
                final int lastDot = matchStorageFile.lastIndexOf('.');
                String prefix = matchStorageFile;
                String suffix = "_mfov_" + mFOVName +"_z_" + pZ + "_to_" + qZ;
                if (lastDot > -1) {
                    prefix = matchStorageFile.substring(0, lastDot);
                    suffix = suffix + matchStorageFile.substring(lastDot);
                }
                storagePath = Paths.get(prefix + suffix).toAbsolutePath();
            }
            return storagePath;
        }

        public void validateAndSetupDerivedValues()
                throws IllegalArgumentException {
            if (matchStorageFile != null) {
                Utilities.validateMatchStorageLocation(matchStorageFile);
            }
            sFOVIndexList = sFOVIndexList.stream().distinct().sorted().collect(Collectors.toList());
            sFOVIndexSet = new HashSet<>(sFOVIndexList);
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

                final MFOVCrossMatchPatchClient client = new MFOVCrossMatchPatchClient(parameters);
                client.loadAndPatchUnconnectedMFOVs();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private RenderDataClient renderDataClient;
    private RenderDataClient matchClient;
    private MultiStagePointMatchClient multiStagePointMatchClient;

    private final Map<Double, ResolvedTileSpecCollection> zToResolvedTiles;
    private List<OrderedCanvasIdPair> unconnectedPairsForMFOV;

    MFOVCrossMatchPatchClient(final Parameters parameters) {
        this.parameters = parameters;
        this.zToResolvedTiles = new HashMap<>();
    }

    public void loadAndPatchUnconnectedMFOVs() throws IOException {
        final List<UnconnectedMFOVPairsForStack> unconnectedMFOVsForAllStacks =
                UnconnectedMFOVPairsForStack.load(parameters.unconnectedMFOVPairsFile);
        for (final UnconnectedMFOVPairsForStack unconnectedMFOVPairsForStack : unconnectedMFOVsForAllStacks) {
            deriveAndSaveMatchesForStack(unconnectedMFOVPairsForStack);
        }
    }

    public void deriveAndSaveMatchesForStack(final UnconnectedMFOVPairsForStack unconnectedMFOVPairsForStack)
            throws IOException {

        final StackId stackId = unconnectedMFOVPairsForStack.getRenderStackId();

        LOG.info("deriveAndSaveMatchesForStack: entry, for {}", stackId);

        renderDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                stackId.getOwner(),
                                                stackId.getProject());
        final String mFOVMontageStack = unconnectedMFOVPairsForStack.getmFOVMontageStackName();

        final MatchCollectionId matchCollectionId = unconnectedMFOVPairsForStack.getMatchCollectionId();
        matchClient = new RenderDataClient(parameters.baseDataUrl,
                                           matchCollectionId.getOwner(),
                                           matchCollectionId.getName());

        final MatchWebServiceParameters matchWebServiceParameters = new MatchWebServiceParameters();
        matchWebServiceParameters.baseDataUrl = parameters.baseDataUrl;
        matchWebServiceParameters.owner = matchCollectionId.getOwner();
        matchWebServiceParameters.collection = matchCollectionId.getName();

        final MultiStagePointMatchClient.Parameters matchDerivationClientParameters =
                new MultiStagePointMatchClient.Parameters(matchWebServiceParameters,
                                                          new FeatureStorageParameters(),
                                                          2,
                                                          false,
                                                          null,
                                                          parameters.stageJson,
                                                          null);


        multiStagePointMatchClient = new MultiStagePointMatchClient(matchDerivationClientParameters);

        zToResolvedTiles.clear();
        final Map<Double, List<OrderedMFOVPair>> pZToPairs = new HashMap<>();
        for (final OrderedMFOVPair pair : unconnectedMFOVPairsForStack.getUnconnectedMFOVPairs()) {
            final double pZ = pair.getP().getZ();
            final List<OrderedMFOVPair> pairs = pZToPairs.computeIfAbsent(pZ, k -> new ArrayList<>());
            pairs.add(pair);
        }

        for (final double pZ : pZToPairs.keySet().stream().sorted().collect(Collectors.toList())) {
            // load tile specs if they have not already been loaded (e.g. for an earlier qZ)
            if (! zToResolvedTiles.containsKey(pZ)) {
                zToResolvedTiles.put(pZ, renderDataClient.getResolvedTiles(mFOVMontageStack, pZ));
            }

            deriveAndSaveMatchesForLayer(stackId, mFOVMontageStack, pZ, pZToPairs.get(pZ));

            // free up pZ tile specs since ordered pairs ensure that they are no longer needed
            zToResolvedTiles.remove(pZ);
        }

        LOG.info("deriveAndSaveMatchesForStack: exit, for {}", stackId);
    }

    public void deriveAndSaveMatchesForLayer(final StackId stackId,
                                             final String mFOVMontageStack,
                                             final Double pZ,
                                             final List<OrderedMFOVPair> mFOVPairsForLayer)
            throws IOException {

        LOG.info("deriveAndSaveMatchesForLayer: entry, pZ={}", pZ);

        for (final OrderedMFOVPair orderedMFOVPair : mFOVPairsForLayer) {

            final Double qZ = orderedMFOVPair.getQ().getZ();
            if (! zToResolvedTiles.containsKey(qZ)) {
                zToResolvedTiles.put(qZ, renderDataClient.getResolvedTiles(mFOVMontageStack, qZ));
            }

            final String pGroupId = pZ.toString();
            final String qGroupId = qZ.toString();
            final String mFOVName = orderedMFOVPair.getP().getName();

            buildUnconnectedData(pZ, qZ, mFOVName);

            if (unconnectedPairsForMFOV.size() > 0) {
                final String urlTemplateString =
                        "{baseDataUrl}/owner/" + stackId.getOwner() + "/project/" +
                        stackId.getProject() +
                        "/stack/" + mFOVMontageStack + "/z/" + TEMPLATE_GROUP_ID_TOKEN +
                        "/render-parameters?tileIdPattern=" +
                        // note: prepend ^ to tileIdPattern to ensure only beginning of each tileId is matched for MFOV
                        URLEncoder.encode("^", StandardCharsets.UTF_8.toString()) + mFOVName;

                final OrderedCanvasIdPair mFOVCanvasPair =
                        new OrderedCanvasIdPair(new CanvasId(pGroupId, "mFOV_" + mFOVName + "." + pGroupId),
                                                new CanvasId(qGroupId, "mFOV_" + mFOVName + "." + qGroupId),
                                                qZ - pZ);

                final RenderableCanvasIdPairs renderableCanvasIdPairs =
                        new RenderableCanvasIdPairs(urlTemplateString,
                                                    Collections.singletonList(mFOVCanvasPair));
                final List<CanvasMatches> mFOVMatchesList =
                        multiStagePointMatchClient.generateMatchesForPairs(renderableCanvasIdPairs);

                storeMatchesForLayer(pZ, qZ, mFOVName, mFOVMatchesList);
            }
        }

        LOG.info("deriveAndSaveMatchesForLayer: exit");
    }

    public void buildUnconnectedData(final Double pZ,
                                     final Double qZ,
                                     final String mFOVName)
            throws IOException {

        LOG.info("buildUnconnectedData: entry, pZ={}, qZ={}, mFOVId={}", pZ, qZ, mFOVName);

        final ResolvedTileSpecCollection pResolvedTiles = zToResolvedTiles.get(pZ);
        final ResolvedTileSpecCollection qResolvedTiles = zToResolvedTiles.get(qZ);

        final Map<String, TileSpec> pSFOVToTileSpec = Utilities.mapMFOVTilesToSFOVIds(pResolvedTiles.getTileSpecs(),
                                                                                      mFOVName);
        final Map<String, TileSpec> qSFOVToTileSpec = Utilities.mapMFOVTilesToSFOVIds(qResolvedTiles.getTileSpecs(),
                                                                                      mFOVName);

        final String pGroupId = pZ.toString();
        final String qGroupId = qZ.toString();
        final Double deltaZ = qZ - pZ;

        // initialize list with all tile pairs between same SFOV tiles in pZ and qZ layers
        final Set<OrderedCanvasIdPair> canvasPairsForMFOV = new HashSet<>();
        for (final String SFOV : pSFOVToTileSpec.keySet()) {
            final TileSpec pTileSpec = pSFOVToTileSpec.get(SFOV);
            final TileSpec qTileSpec = qSFOVToTileSpec.get(SFOV);
            if (qTileSpec != null) {
                final OrderedCanvasIdPair pair = new OrderedCanvasIdPair(new CanvasId(pGroupId, pTileSpec.getTileId()),
                                                                         new CanvasId(qGroupId, qTileSpec.getTileId()),
                                                                         deltaZ);
                canvasPairsForMFOV.add(pair);
            }
        }

        // query web service to find connected tile pairs and remove them from pairs set
        if (canvasPairsForMFOV.size() > 0) {
            for (final CanvasMatches canvasMatches : matchClient.getMatchesBetweenGroups(pGroupId,
                                                                                         qGroupId,
                                                                                         true)) {
                final String pId = canvasMatches.getpId();
                final String qId = canvasMatches.getqId();
                if (pId.startsWith(mFOVName) && qId.startsWith(mFOVName)) {
                    final OrderedCanvasIdPair pair = new OrderedCanvasIdPair(new CanvasId(pGroupId, pId),
                                                                             new CanvasId(qGroupId, qId),
                                                                             deltaZ);
                    if (! canvasPairsForMFOV.remove(pair)) {
                        LOG.warn("buildUnconnectedData: failed to locate existing pair {}",
                                 pair);
                    }
                }
            }
        }

        // any pairs left in the set are unconnected
        unconnectedPairsForMFOV = canvasPairsForMFOV.stream().sorted().collect(Collectors.toList());

        LOG.info("buildUnconnectedData: exit, found {} unconnected tile pairs within mFOV {} in z {}",
                 unconnectedPairsForMFOV.size(), mFOVName, pZ);
    }

    private void storeMatchesForLayer(final Double pZ,
                                      final Double qZ,
                                      final String mFOVName,
                                      final List<CanvasMatches> mFOVMatchesList)
            throws IOException {

        final String context = "mFOV " + mFOVName + " tile pairs between z " + pZ + " and " + qZ;

        if (mFOVMatchesList.size() == 1) {

            final List<PointMatch> mFOVMatches =
                    CanvasMatchResult.convertMatchesToPointMatchList(mFOVMatchesList.get(0).getMatches());

            final List<CanvasMatches> sFOVMatchesList = buildMatches(pZ,
                                                                     qZ,
                                                                     mFOVMatches,
                                                                     context);

            if (sFOVMatchesList.size() > 0) {
                LOG.info("storeMatchesForLayer: saving matches for {}", context);

                if (parameters.matchStorageFile != null) {
                    final Path storagePath = parameters.getStoragePath(pZ, qZ, mFOVName);
                    FileUtil.saveJsonFile(storagePath.toString(), sFOVMatchesList);
                } else {
                    matchClient.saveMatches(sFOVMatchesList);
                }
            } else {
                LOG.warn("storeMatchesForLayer: no sFOV matches derived for {}", context);
            }

        } else {
            LOG.error("storeMatchesForLayer: no mFOV matches derived for {}", context);
        }
    }

    private List<CanvasMatches> buildMatches(final Double pZ,
                                             final Double qZ,
                                             final List<PointMatch> mFOVMatches,
                                             final String context)
            throws IOException {

        final List<CanvasMatches> sFOVMatchesList = new ArrayList<>();

        final List<OrderedCanvasIdPair> unconnectedPairsWithSFOVIndex = new ArrayList<>();

        OrderedCanvasIdPair firstUnconnectedPairWithWrongSFOVIndex = null;
        for (final OrderedCanvasIdPair canvasPair : unconnectedPairsForMFOV) {
            final String sFOV = Utilities.getSFOVForTileId(canvasPair.getP().getId());
            final String sFOVIndex = sFOV.substring(sFOV.length() - 3);
            if (parameters.sFOVIndexSet.contains(sFOVIndex)) {
                unconnectedPairsWithSFOVIndex.add(canvasPair);
            } else if (firstUnconnectedPairWithWrongSFOVIndex == null) {
                firstUnconnectedPairWithWrongSFOVIndex = canvasPair;
            }
        }

        if (unconnectedPairsWithSFOVIndex.size() == 0) {
            if (firstUnconnectedPairWithWrongSFOVIndex == null) {
                LOG.warn("buildMatches: no unconnected pairs exist for {}", context);
            } else {
                LOG.warn("buildMatches: no unconnected pairs with SFOVIndex {} exist for {}, using {} instead",
                         parameters.sFOVIndexList, context, firstUnconnectedPairWithWrongSFOVIndex);
                unconnectedPairsWithSFOVIndex.add(firstUnconnectedPairWithWrongSFOVIndex);
            }
        }

        Collections.sort(unconnectedPairsWithSFOVIndex);
        for (final OrderedCanvasIdPair sFOVPair : unconnectedPairsWithSFOVIndex) {
            sFOVMatchesList.add(
                    buildMatchesUsingInvertedTransformForSFOVPair(pZ,
                                                                  qZ,
                                                                  mFOVMatches,
                                                                  sFOVPair,
                                                                  parameters.storedMatchWeight));
        }

        return sFOVMatchesList;
    }

    private CanvasMatches buildMatchesUsingInvertedTransformForSFOVPair(final Double pZ,
                                                                        final Double qZ,
                                                                        final List<PointMatch> mFOVMatches,
                                                                        final OrderedCanvasIdPair sFOVPair,
                                                                        final double derivedMatchWeight)
            throws IOException {

        final CanvasId p = sFOVPair.getP();
        final CanvasId q = sFOVPair.getQ();

        final ResolvedTileSpecCollection pResolvedTiles = zToResolvedTiles.get(pZ);
        final ResolvedTileSpecCollection qResolvedTiles = zToResolvedTiles.get(qZ);

        final TileSpec pTileSpec = pResolvedTiles.getTileSpec(p.getId());
        final TileSpec qTileSpec = qResolvedTiles.getTileSpec(q.getId());

        final List<Point> pPoints = Utilities.transformMFOVMatchesForTile(mFOVMatches, pTileSpec, true);
        final List<Point> qPoints = Utilities.transformMFOVMatchesForTile(mFOVMatches, qTileSpec, false);

        final List<PointMatch> matchList = new ArrayList<>(pPoints.size());
        for (int i = 0; i < pPoints.size(); i++) {
            final Point pPoint = pPoints.get(i);
            final Point qPoint = qPoints.get(i);
            if ((pPoint != null) && (qPoint != null)) {
                matchList.add(new PointMatch(pPoints.get(i), qPoints.get(i), derivedMatchWeight));
            }
        }

        if (matchList.size() == 0) {
            throw new IOException("unable to invert matches for sFOV pair " + sFOVPair);
        }

        return new CanvasMatches(p.getGroupId(),
                                 p.getId(),
                                 q.getGroupId(),
                                 q.getId(),
                                 CanvasMatchResult.convertPointMatchListToMatches(matchList,
                                                                                  1.0));
    }

    private static final Logger LOG = LoggerFactory.getLogger(MFOVCrossMatchPatchClient.class);
}

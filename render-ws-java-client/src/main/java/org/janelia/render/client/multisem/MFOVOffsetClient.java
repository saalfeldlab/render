package org.janelia.render.client.multisem;

import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import mpicbg.models.PointMatch;
import mpicbg.models.TranslationModel2D;

import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.match.MatchFilter;
import org.janelia.alignment.match.MatchTrial;
import org.janelia.alignment.match.ModelType;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.parameters.FeatureAndMatchParameters;
import org.janelia.alignment.match.parameters.FeatureExtractionParameters;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.janelia.alignment.match.parameters.MatchTrialParameters;
import org.janelia.alignment.multisem.LayerMFOV;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.alignment.util.RenderWebServiceUrls;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MFOVOffsetParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.janelia.alignment.match.CanvasMatchResult.convertMatchesToPointMatchList;

/**
 * Java client that looks at potential tile pairs between MFOVs in adjacent z layers
 * to calculate offsets for each stack layer.  The offsets are then applied to
 * each tile in the stack to create an offset stack.
 */
public class MFOVOffsetClient {

    public static class Parameters
            extends CommandLineParameters {

        @ParametersDelegate
        public MultiProjectParameters multiProject = new MultiProjectParameters();

        @ParametersDelegate
        public MFOVOffsetParameters mfovOffset = new MFOVOffsetParameters();
    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args)
                    throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final MFOVOffsetClient client = new MFOVOffsetClient(parameters);
                client.buildAllOffsetStacks();

                LOG.info("runClient: exit");
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;


    public MFOVOffsetClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    /**
     * Builds offset stacks for all stacks identified in the parameters.
     *
     * @throws IOException
     *   if the build fails for any reason.
     */
    public void buildAllOffsetStacks()
            throws IOException, IllegalStateException {

        final RenderDataClient renderDataClient = parameters.multiProject.getDataClient();
        final List<StackWithZValues> stackWithZList = parameters.multiProject.buildListOfStackWithAllZ();

        for (final StackWithZValues stackWithZ : stackWithZList) {
            final MatchCollectionId matchCollectionId =
                    parameters.multiProject.getMatchCollectionIdForStack(stackWithZ.getStackId());
            final RenderDataClient matchDataClient = renderDataClient.buildClient(matchCollectionId.getOwner(),
                                                                                  matchCollectionId.getName());
            buildOneOffsetStack(stackWithZ,
                                renderDataClient,
                                matchDataClient,
                                parameters.mfovOffset);
        }
    }

    /**
     * Builds an offset stack for the specified stack with Z values.
     *
     * @param  stackWithZ             identifies the stack to process.
     * @param  renderDataClient       web service client for render stack data.
     * @param  matchDataClient        web service client for match data.
     * @param  mfovOffsetParameters   parameters for the MFOV offset calculation.
     *
     * @throws IOException
     *   if the build fails for any reason.
     */
    public static void buildOneOffsetStack(final StackWithZValues stackWithZ,
                                           final RenderDataClient renderDataClient,
                                           final RenderDataClient matchDataClient,
                                           final MFOVOffsetParameters mfovOffsetParameters)
            throws IOException, IllegalStateException {

        final StackId offsetStackId = stackWithZ.getStackId().withStackSuffix(mfovOffsetParameters.offsetStackSuffix);

        // 1. Use number of connected match points to order same layer tile pairs in each MFOV of the first z layer.
        final MFOVOffsetSupportData mfovOffsetSupportData =
                new MFOVOffsetSupportData(stackWithZ,
                                          mfovOffsetParameters.minimumNumberOfTilesForIncludedMFOVs);
        mfovOffsetSupportData.buildCollections(renderDataClient, matchDataClient);

        // 2. Select the top connected first layer tile in each MFOV and use its position to
        //    derive ordered lists of potential cross layer match pairs.
        //    Potential MFOV cross layer pairs are only built for adjacent layers (e.g. z 1 to 2, 2 to 3, etc.).
        final List<SortedNearestCrossPairsForLayerMFOV> sortedNearestPairsList =
                SortedNearestCrossPairsForLayerMFOV.buildPairsLists(stackWithZ,
                                                                    renderDataClient,
                                                                    mfovOffsetParameters,
                                                                    mfovOffsetSupportData);

        // 3. For each ordered list of potential cross layer match pairs, find the first pair
        //    that has matches and save them to calculate a translation offset later.

        // TODO: parallelize processing for each sortedNearestPairs object in list

        final FeatureAndMatchParameters crossFeatureAndMatchParameters =
                buildFeatureAndMatchParameters(mfovOffsetParameters.renderScale,
                                               mfovOffsetParameters.minNumberOfMatchInliers,
                                               5.0,
                                               MatchFilter.FilterType.SINGLE_SET);

        final Map<Double, Map<LayerMFOV, List<PointMatch>>> layerZToMatchesMap = new HashMap<>();

        final int sortedNearestPairsCount = sortedNearestPairsList.size();
        for (int i = 0; i < sortedNearestPairsCount; i++) {
            final SortedNearestCrossPairsForLayerMFOV nearestCrossPairs = sortedNearestPairsList.get(i);
            final LayerMFOV layerMFOV = nearestCrossPairs.getLayerMFOV();

            LOG.info("buildOneOffsetStack: derive matches for {} ({} of {})",
                     layerMFOV, (i+1), sortedNearestPairsCount);

            final List<PointMatch> layerMFOVMatches =
                    deriveMatchesForLayerMFOV(stackWithZ,
                                              renderDataClient,
                                              mfovOffsetSupportData,
                                              nearestCrossPairs,
                                              mfovOffsetParameters.renderScale,
                                              crossFeatureAndMatchParameters);

            final Map<LayerMFOV, List<PointMatch>> layerMFOVToMatchesMap =
                    layerZToMatchesMap.computeIfAbsent(layerMFOV.getZ(), k -> new HashMap<>());
            layerMFOVToMatchesMap.put(layerMFOV, layerMFOVMatches);
        }

        // 4. Calculate the offset for each MFOV of each z layer
        //    by combining matches from all MFOVs and then run ransac filter taking the inliers.
        //    The inliers should be a high number (> 95%).
        //    Pull translation vector from the inliers.

        final MatchFilter singleSetMatchFilter = new MatchFilter(crossFeatureAndMatchParameters.getMatchDerivationParameters(),
                                                                 mfovOffsetParameters.renderScale);

        final MatchDerivationParameters aggregatedLayerMatchDerivationParameters =
                buildFeatureAndMatchParameters(mfovOffsetParameters.renderScale,
                                               mfovOffsetParameters.minNumberOfMatchInliers,
                                               2000,
                                               MatchFilter.FilterType.AGGREGATED_CONSENSUS_SETS).getMatchDerivationParameters();

        final MatchFilter aggregatedSetMatchFilter = new MatchFilter(aggregatedLayerMatchDerivationParameters,
                                                                     mfovOffsetParameters.renderScale);

        final Map<Double, double[]> nextZToTranslationMap = new HashMap<>();
        final List<Double> zValues = stackWithZ.getzValues();
        final double[] maxActualAbsoluteMFOVTranslationDelta = new double[] {0.0, 0.0};

        for (int nextZIndex = 1; nextZIndex < zValues.size(); nextZIndex++) {

            final double z = zValues.get(nextZIndex - 1);
            final double nextZ = zValues.get(nextZIndex);

            final Map<LayerMFOV, List<PointMatch>> layerMFOVToMatchesMap = layerZToMatchesMap.get(z);
            if (layerMFOVToMatchesMap == null) {
                LOG.warn("buildOneOffsetStack: no matches found for z {}", z);
            } else {

                final List<LayerMFOV> sortedMFOVsForCurrentLayer =
                        layerMFOVToMatchesMap.keySet().stream().sorted().collect(Collectors.toList());
                final List<double[]> nextLayerMFOVTranslations = new ArrayList<>(sortedMFOVsForCurrentLayer.size());

                final List<PointMatch> layerMatches = new ArrayList<>();
                for (final LayerMFOV layerMFOV : sortedMFOVsForCurrentLayer) {
                    final List<PointMatch> layerMFOVMatches = layerMFOVToMatchesMap.get(layerMFOV);
                    final double[] nextLayerMFOVTranslation = deriveNextLayerTranslation(layerMFOV.toString(),
                                                                                         layerMFOVMatches,
                                                                                         singleSetMatchFilter);
                    nextLayerMFOVTranslations.add(nextLayerMFOVTranslation);
                    layerMatches.addAll(layerMFOVMatches);
                }

                // 5. For each z layer if any MFOVs have an offset that is significantly different from the others
                //    log the large difference and fail the run since this should rarely occur.
                final double[] maxActualAbsoluteMFOVTranslationDeltaForLayer =
                        checkNextLayerMFOVTranslationConsistency(stackWithZ.getStackId(),
                                                                 sortedMFOVsForCurrentLayer,
                                                                 nextLayerMFOVTranslations,
                                                                 mfovOffsetParameters.maxAbsoluteMFOVTranslationDelta);

                maxActualAbsoluteMFOVTranslationDelta[0] = Math.max(maxActualAbsoluteMFOVTranslationDelta[0],
                                                                    maxActualAbsoluteMFOVTranslationDeltaForLayer[0]);
                maxActualAbsoluteMFOVTranslationDelta[1] = Math.max(maxActualAbsoluteMFOVTranslationDelta[1],
                                                                    maxActualAbsoluteMFOVTranslationDeltaForLayer[1]);

                final double[] nextLayerTranslation = deriveNextLayerTranslation("z " + z,
                                                                                 layerMatches,
                                                                                 aggregatedSetMatchFilter);
                nextZToTranslationMap.put(nextZ, nextLayerTranslation);

            }
        }

        // worst deltas from early test:
        //   translation delta is -1096.5142945149448,  -699.1644233276263 between z_20.0_mfov_0160_m0031 and z_20.0_mfov_0160_m0032
        //   translation delta is    50.76237361117569, 1154.7718412848626 between z_20.0_mfov_0160_m0032 and z_20.0_mfov_0160_m0033
        //   maxActualAbsoluteMFOVTranslationDelta is 1096.5142945149448, 1154.7718412848626

        LOG.info("buildOneOffsetStack: maxActualAbsoluteMFOVTranslationDelta is {}, {}",
                 maxActualAbsoluteMFOVTranslationDelta[0], maxActualAbsoluteMFOVTranslationDelta[1]);

        // 6. Apply the layer offsets, saving the resulting tile specs to the offset stack.
        saveOffsetStack(stackWithZ,
                        offsetStackId,
                        renderDataClient,
                        nextZToTranslationMap);

    }

    @SuppressWarnings("SameParameterValue")
    private static List<PointMatch> deriveMatchesForLayerMFOV(final StackWithZValues stackWithZ,
                                                              final RenderDataClient renderDataClient,
                                                              final MFOVOffsetSupportData mfovOffsetSupportData,
                                                              final SortedNearestCrossPairsForLayerMFOV nearestPairsList,
                                                              final double renderScale,
                                                              final FeatureAndMatchParameters crossFeatureAndMatchParameters) {

        List<PointMatch> layerMFOVMatches = null;

        final RenderWebServiceUrls urls = renderDataClient.getUrls();
        final String urlPrefix = urls.getStackUrlString(stackWithZ.getStackId().getStack()) + "/tile/";
        final String urlSuffix = "/render-parameters?excludeMask=false&normalizeForMatching=true&scale=" + renderScale;

        final List<String> nearestPairsListTileIds = nearestPairsList.getSortedTileIds();
        final int tileCount = nearestPairsListTileIds.size();
        for (int tileIndex = 0; tileIndex < tileCount; tileIndex++) {

            final String tileId = nearestPairsListTileIds.get(tileIndex);

            LOG.info("deriveMatchesForLayerMFOV: process tile {} of {} with id {} and connection score {}",
                     (tileIndex+1), tileCount, tileId, mfovOffsetSupportData.getConnectionScoreForTile(tileId));

            final List<OrderedCanvasIdPair> nearestPairs =
                    nearestPairsList.getSortedNearestPairsForTileId(tileId);
            final int nearestPairsCount = nearestPairs.size();
            for (int pairIndex = 0; pairIndex < nearestPairsCount; pairIndex++) {

                final OrderedCanvasIdPair pair = nearestPairs.get(pairIndex);

                LOG.info("deriveMatchesForLayerMFOV: process pair {} ({} of {})",
                         pair, (pairIndex+1), nearestPairsCount);

                final String pTileId = pair.getP().getId();
                final String pRenderParametersUrl = urlPrefix + pTileId + urlSuffix;

                final String qTileId = pair.getQ().getId();
                final String qRenderParametersUrl = urlPrefix + qTileId + urlSuffix;

                final MatchTrialParameters matchTrialParameters =
                        new MatchTrialParameters(crossFeatureAndMatchParameters,
                                                 pRenderParametersUrl,
                                                 qRenderParametersUrl,
                                                 null);

                final MatchTrial matchTrial = new MatchTrial(matchTrialParameters);

                // specify match trial groupIds so that cross layer tile pairs are always ordered properly (e.g. for z 9 to 10)
                matchTrial.deriveResults(ImageProcessorCache.DISABLED_CACHE, "pGroupId", "qGroupId");

                if (matchTrial.hasMatches()) {
                    layerMFOVMatches = convertMatchesToPointMatchList(matchTrial.getMatches().get(0));
                    LOG.info("deriveMatchesForLayerMFOV: found {} match points for pair {}",
                             layerMFOVMatches.size(), pair);
                    break;
                }

            }

        }

        return layerMFOVMatches;
    }

    private static FeatureAndMatchParameters buildFeatureAndMatchParameters(final double renderScale,
                                                                            final int minNumInliers,
                                                                            final double maxEpsilonFullScale,
                                                                            final MatchFilter.FilterType filterType) {

        final FeatureExtractionParameters siftFeatureParameters = new FeatureExtractionParameters();
        siftFeatureParameters.fdSize = 4;
        siftFeatureParameters.minScale = 0.125;
        siftFeatureParameters.maxScale = 1.0;
        siftFeatureParameters.steps = 5;

        final double maxEpsilon = maxEpsilonFullScale * (1.0 / renderScale);
        final MatchDerivationParameters matchDerivationParameters =
                new MatchDerivationParameters(0.92f,
                                              ModelType.TRANSLATION,
                                              1000,
                                              (float) maxEpsilon,
                                              0.0f,
                                              minNumInliers,
                                              4.0,
                                              null,
                                              filterType);
        matchDerivationParameters.matchFullScaleCoverageRadius = 300.0;

        return new FeatureAndMatchParameters(siftFeatureParameters,
                                             matchDerivationParameters,
                                             null,
                                             null);
    }

    private static double[] deriveNextLayerTranslation(final String context,
                                                       final List<PointMatch> candidates,
                                                       final MatchFilter matchFilter) {
        final TranslationModel2D model = new TranslationModel2D();
        final List<PointMatch> inliers = matchFilter.filterMatches(candidates, model);
        final double[] currentZTranslation = model.getTranslation();

        // for one test with pair w60_magc0160_scan004_m0031_r89_s85 and w60_magc0160_scan005_m0031_r89_s85:
        //   translation was 104, -188 but actual scan005 translation was -84,  118
        //   so need to flip signs (assume it is ok to ignore deltaX 20, deltaY -70)
        final double[] nextTranslation = new double[] {-currentZTranslation[0], -currentZTranslation[1]};

        LOG.info("deriveNextLayerTranslation: returning {}, {} for {} inliers from {} {} candidates",
                 nextTranslation[0], nextTranslation[1], inliers.size(), candidates.size(), context);

        return nextTranslation;
    }

    private static double[] checkNextLayerMFOVTranslationConsistency(final StackId stackId,
                                                                     final List<LayerMFOV> sortedMFOVsForCurrentLayer,
                                                                     final List<double[]> nextLayerMFOVTranslations,
                                                                     final Integer maxAbsoluteMFOVTranslationDelta)
            throws IllegalStateException {

        double maxTranslationDeltaX = 0.0;
        double maxTranslationDeltaY = 0.0;

        for (int mfovIndex = 1; mfovIndex < sortedMFOVsForCurrentLayer.size(); mfovIndex++) {

            final int previousMFOVIndex = mfovIndex - 1;
            final LayerMFOV previousMFOV = sortedMFOVsForCurrentLayer.get(previousMFOVIndex);
            final double[] previousMFOVTranslation = nextLayerMFOVTranslations.get(previousMFOVIndex);

            final LayerMFOV currentMFOV = sortedMFOVsForCurrentLayer.get(mfovIndex);
            final double[] currentMFOVTranslation = nextLayerMFOVTranslations.get(mfovIndex);

            final double[] deltaTranslation = new double[] {
                    currentMFOVTranslation[0] - previousMFOVTranslation[0],
                    currentMFOVTranslation[1] - previousMFOVTranslation[1]
            };

            LOG.info("checkNextLayerMFOVTranslationConsistency: translation delta is {}, {} between {} and {} in {}",
                     deltaTranslation[0], deltaTranslation[1], previousMFOV, currentMFOV, stackId.toDevString());

            maxTranslationDeltaX = Math.max(maxTranslationDeltaX, Math.abs(deltaTranslation[0]));
            maxTranslationDeltaY = Math.max(maxTranslationDeltaY, Math.abs(deltaTranslation[1]));

            if (maxAbsoluteMFOVTranslationDelta != null) {
                if ((Math.abs(deltaTranslation[0]) > maxAbsoluteMFOVTranslationDelta) ||
                    (Math.abs(deltaTranslation[1]) > maxAbsoluteMFOVTranslationDelta)) {
                    throw new IllegalStateException(
                            "translation delta " + deltaTranslation[0] + ", " + deltaTranslation[1] +
                            " between " + previousMFOV + " and " + currentMFOV + " of " + stackId.toDevString() +
                            " exceeds --mopMaxAbsoluteMFOVTranslationDelta " + maxAbsoluteMFOVTranslationDelta);
                }
            }

        }

        return new double[] {maxTranslationDeltaX, maxTranslationDeltaY};
    }

    private static void saveOffsetStack(final StackWithZValues stackWithZ,
                                        final StackId offsetStackId,
                                        final RenderDataClient renderDataClient,
                                        final Map<Double, double[]> nextZToTranslationMap)
            throws IOException {

        final String stackName = stackWithZ.getStackId().getStack();
        final String offsetStackName = offsetStackId.getStack();

        final StackMetaData stackMetaData = renderDataClient.getStackMetaData(stackName);
        final StackMetaData offsetStackMetaData = renderDataClient.setupDerivedStack(stackMetaData, offsetStackName);

        final List<Double> zValues = stackWithZ.getzValues();
        final Double firstZ = zValues.get(0);

        ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(stackName, firstZ);
        renderDataClient.saveResolvedTiles(resolvedTiles, offsetStackName, firstZ);

        final int[] stackTranslation = {0, 0};
        for (int zIndex = 1; zIndex < zValues.size(); zIndex++) {
            final double z = zValues.get(zIndex);
            final double[] layerTranslation = nextZToTranslationMap.get(z);
            if (layerTranslation != null) {

                // convert relative layer translation to one for the stack
                stackTranslation[0] += (int) layerTranslation[0];
                stackTranslation[1] += (int) layerTranslation[1];

                resolvedTiles = renderDataClient.getResolvedTiles(stackName, z);

                final String translationDataString = stackTranslation[0] + " " + stackTranslation[1];

                LOG.info("saveOffsetStack: pre-concatenating stack translation {} to all tiles in z {} of {}, relative layer translation is {}, {}",
                         translationDataString, z, offsetStackMetaData.getStackId().toDevString(), layerTranslation[0], layerTranslation[1]);

                final LeafTransformSpec leafTransformSpec =
                        new LeafTransformSpec("mpicbg.trakem2.transform.TranslationModel2D",
                                              translationDataString);
                resolvedTiles.preConcatenateTransformToAllTiles(leafTransformSpec);

            } else {
                LOG.warn("saveOffsetStack: no offset found for z {}", z);
            }

            renderDataClient.saveResolvedTiles(resolvedTiles, offsetStackName, z);
        }

        renderDataClient.setStackState(offsetStackName, StackMetaData.StackState.COMPLETE);
    }

    private static final Logger LOG = LoggerFactory.getLogger(MFOVOffsetClient.class);
}

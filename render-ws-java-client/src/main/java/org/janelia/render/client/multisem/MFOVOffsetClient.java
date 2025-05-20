package org.janelia.render.client.multisem;

import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.util.List;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.match.MatchFilter;
import org.janelia.alignment.match.MatchTrial;
import org.janelia.alignment.match.Matches;
import org.janelia.alignment.match.ModelType;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.parameters.FeatureAndMatchParameters;
import org.janelia.alignment.match.parameters.FeatureExtractionParameters;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.janelia.alignment.match.parameters.MatchTrialParameters;
import org.janelia.alignment.multisem.LayerMFOV;
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
            throws IOException {

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
            throws IOException {

        // 1. Use number of connected match points to order same layer tile pairs in each MFOV of the first z layer.
        final MFOVOffsetSupportData mfovOffsetSupportData =
                new MFOVOffsetSupportData(stackWithZ,
                                          mfovOffsetParameters.minimumNumberOfTilesForIncludedMFOVs);
        mfovOffsetSupportData.buildCollections(renderDataClient, matchDataClient);

        // 2. Select the top connected first layer tiles in each MFOV and use their position to
        //    derive ordered lists of potential cross layer match pairs.
        //    Potential MFOV cross layer pairs are only built for adjacent layers (e.g. z 1 to 2, 2 to 3, etc.).
        final List<SortedNearestCrossPairsForLayerMFOV> sortedNearestPairsList =
                SortedNearestCrossPairsForLayerMFOV.buildPairsLists(stackWithZ,
                                                                    renderDataClient,
                                                                    mfovOffsetParameters,
                                                                    mfovOffsetSupportData);

        // TODO: MFOV offset steps 3 and 4

        // 3. For each ordered list of potential cross layer match pairs, find the first pair
        //    that has matches and calculate the translation offset for that pair.

        // 4. Calculate the offset for each MFOV of each z layer
        //    by averaging the layer MFOV's match translation offsets.

        final int minNumberOfMatchInliers = 10; // TODO: make this a parameter
        final double renderScale = 0.4;         // TODO: make this a parameter

        // TODO: parallelize processing for each sortedNearestPairs object in list

        final int sortedNearestPairsCount = sortedNearestPairsList.size();
        for (int i = 0; i < sortedNearestPairsCount; i++) {

            final SortedNearestCrossPairsForLayerMFOV nearestCrossPairs = sortedNearestPairsList.get(i);
            final LayerMFOV layerMFOV = nearestCrossPairs.getLayerMFOV();

            LOG.info("buildOneOffsetStack: process {} ({} of {})",
                     layerMFOV, (i+1), sortedNearestPairsCount);

            final Matches matches = deriveMatchesForLayerMFOV(stackWithZ,
                                                              renderDataClient,
                                                              mfovOffsetSupportData,
                                                              nearestCrossPairs,
                                                              minNumberOfMatchInliers,
                                                              renderScale);

            final CanvasMatches canvasMatches = new CanvasMatches(String.valueOf(layerMFOV.getZ()),
                                                                  layerMFOV.getName(),
                                                                  String.valueOf(layerMFOV.getZ() + 1.0),
                                                                  layerMFOV.getName(),
                                                                  matches);
            System.out.println(canvasMatches.toJson());

            // TODO: combine matches from all MFOVs and run ransac, take inliers from all, should be a high number (> 95%) and pull translation vector from that

        }


        // TODO: MFOV offset step 5

        // 5. For each z layer if any MFOVs have an offset that is significantly different from the others
        //    log the large difference and fail the run since this should rarely occur.

        // TODO: MFOV offset step 6

        // 6. Otherwise, calculate a median offset for the layer (factoring in prior layer offsets).

        // TODO: MFOV offset step 7

        // 7. Apply the layer offsets, saving the resulting tile specs to the offset stack.
    }

    @SuppressWarnings("SameParameterValue")
    private static Matches deriveMatchesForLayerMFOV(final StackWithZValues stackWithZ,
                                                     final RenderDataClient renderDataClient,
                                                     final MFOVOffsetSupportData mfovOffsetSupportData,
                                                     final SortedNearestCrossPairsForLayerMFOV nearestPairsList,
                                                     final int minNumberOfMatchInliers,
                                                     final double renderScale) {

        Matches layerMFOVMatches = null;

        final RenderWebServiceUrls urls = renderDataClient.getUrls();
        final String urlPrefix = urls.getStackUrlString(stackWithZ.getStackId().getStack()) + "/tile/";
        final String urlSuffix = "/render-parameters?excludeMask=false&normalizeForMatching=true&scale=" + renderScale;

        final FeatureAndMatchParameters crossFeatureAndMatchParameters =
                buildFeatureAndMatchParameters(renderScale,
                                               minNumberOfMatchInliers);

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
                matchTrial.deriveResults(ImageProcessorCache.DISABLED_CACHE);

                if (matchTrial.hasMatches()) {
                    layerMFOVMatches = matchTrial.getMatches().get(0);
                    LOG.info("deriveMatchesForLayerMFOV: found {} match points for pair {}",
                             layerMFOVMatches.getWs().length, pair);
                    break;
                }

            }

        }

        return layerMFOVMatches;
    }

    @SuppressWarnings("ExtractMethodRecommender")
    private static FeatureAndMatchParameters buildFeatureAndMatchParameters(final double renderScale,
                                                                            final int minNumInliers) {

        final FeatureExtractionParameters siftFeatureParameters = new FeatureExtractionParameters();
        siftFeatureParameters.fdSize = 4;
        siftFeatureParameters.minScale = 0.125;
        siftFeatureParameters.maxScale = 1.0;
        siftFeatureParameters.steps = 5;

        final double maxEpsilonFullScale = 5.0;
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
                                              MatchFilter.FilterType.SINGLE_SET);
        matchDerivationParameters.matchFullScaleCoverageRadius = 300.0;

        return new FeatureAndMatchParameters(siftFeatureParameters,
                                             matchDerivationParameters,
                                             null,
                                             null);
    }

    private static final Logger LOG = LoggerFactory.getLogger(MFOVOffsetClient.class);
}

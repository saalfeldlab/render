package org.janelia.alignment.match;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.match.parameters.FeatureAndMatchParameters;
import org.janelia.alignment.match.parameters.FeatureExtractionParameters;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.janelia.alignment.match.parameters.MatchTrialParameters;

/**
 * Tests the {@link MatchTrial} class.
 *
 * @author Eric Trautman
 */
public class MatchTrialTest {

    public static void main(final String[] args) {

        final String baseTileUrl = "http://renderer-dev:8080/render-ws/v1/owner/flyTEM/project/spc_mm2_sample_rough_test_1_tier_2/stack/0009x0009_000023/tile";
        final String pTile = "/z_1015.0_box_9216_3072_2048_2048_0.250000";
        final String qTile = "/z_1016.0_box_9216_3072_2048_2048_0.250000";
//        final String qTile = "/z_1017.0_box_9060_2614_2048_2048_0.250000";
        final String queryParameters = "?excludeMask=true&normalizeForMatching=true&filter=true";

        final String pTileUrl = baseTileUrl + pTile + "/render-parameters" + queryParameters;
        final String qTileUrl = baseTileUrl + qTile + "/render-parameters" + queryParameters;

        final FeatureExtractionParameters featureExtractionParameters = new FeatureExtractionParameters();
        featureExtractionParameters.fdSize = 4;
        featureExtractionParameters.minScale = 0.5;
        featureExtractionParameters.maxScale = 1.0;
        featureExtractionParameters.steps = 3;

        final MatchDerivationParameters matchDerivationParameters = new MatchDerivationParameters();
        matchDerivationParameters.matchRod = 0.95f;
        matchDerivationParameters.matchModelType = ModelType.AFFINE;
        matchDerivationParameters.matchRegularizerModelType = ModelType.RIGID;
        matchDerivationParameters.matchInterpolatedModelLambda = 0.1;
        matchDerivationParameters.matchIterations = 1000;
        matchDerivationParameters.matchMaxEpsilon = 5.0f;
        matchDerivationParameters.matchMinInlierRatio = 0.0f;
        matchDerivationParameters.matchMaxTrust = 30.0;
        matchDerivationParameters.matchMinNumInliers = 20;
        matchDerivationParameters.matchFilter = CanvasFeatureMatcher.FilterType.AGGREGATED_CONSENSUS_SETS;

        final FeatureAndMatchParameters featureAndMatchParameters =
                new FeatureAndMatchParameters(featureExtractionParameters,
                                              matchDerivationParameters,
                                              null,
                                              null);

        final MatchTrialParameters trialParameters = new MatchTrialParameters(featureAndMatchParameters,
                                                                              pTileUrl,
                                                                              qTileUrl);

        final JsonUtils.Helper<MatchTrialParameters> JSON_HELPER =
                new JsonUtils.Helper<>(MatchTrialParameters.class);

        System.out.println(JSON_HELPER.toJson(trialParameters));

        final MatchTrial matchTrial = new MatchTrial(trialParameters);
        matchTrial.deriveResults();

        System.out.println(matchTrial.getStats().toJson());
    }
}

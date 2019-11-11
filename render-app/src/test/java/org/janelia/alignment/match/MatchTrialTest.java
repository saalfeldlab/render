package org.janelia.alignment.match;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.match.parameters.FeatureAndMatchParameters;
import org.janelia.alignment.match.parameters.FeatureExtractionParameters;
import org.janelia.alignment.match.parameters.GeometricDescriptorAndMatchFilterParameters;
import org.janelia.alignment.match.parameters.GeometricDescriptorParameters;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.janelia.alignment.match.parameters.MatchTrialParameters;

import static org.janelia.alignment.match.MatchFilter.*;

/**
 * Tests the {@link MatchTrial} class.
 *
 * @author Eric Trautman
 */
public class MatchTrialTest {

    public static void main(final String[] args) {

        final double siftRenderScale = 0.15;
        final boolean siftDoFilter = true;
        final boolean siftExcludeMask = false;

        final String baseTileUrl = "http://renderer-dev.int.janelia.org:8080/render-ws/v1/owner/Z1217_19m/project/Sec07/stack/v1_acquire/tile/";
        final String urlSuffix = "/render-parameters?scale=" + siftRenderScale +
                                 "&excludeMask=" + siftExcludeMask +
                                 "&filter=" + siftDoFilter;

        final String pTileId = "19-02-21_105501_0-0-0.26101.0";
        final String qTileId = "19-02-21_161150_0-0-0.26102.0";

        final String pTileUrl = baseTileUrl + pTileId + urlSuffix;
        final String qTileUrl = baseTileUrl + qTileId + urlSuffix;

        final FeatureExtractionParameters siftFeatureParameters = new FeatureExtractionParameters();
        siftFeatureParameters.fdSize = 4;
        siftFeatureParameters.minScale = 0.25;
        siftFeatureParameters.maxScale = 1.0;
        siftFeatureParameters.steps = 3;

        final MatchDerivationParameters siftMatchParameters = new MatchDerivationParameters();
        siftMatchParameters.matchRod = 0.92f;
        siftMatchParameters.matchModelType = ModelType.AFFINE;
        siftMatchParameters.matchRegularizerModelType = ModelType.RIGID;
        siftMatchParameters.matchInterpolatedModelLambda = 0.25;
        siftMatchParameters.matchIterations = 1000;
        siftMatchParameters.matchMaxEpsilon = 50.0f;
        siftMatchParameters.matchMinInlierRatio = 0.0f;
        siftMatchParameters.matchMinNumInliers = 10;
        siftMatchParameters.matchMaxTrust = 4.0;
        siftMatchParameters.matchFilter = FilterType.SINGLE_SET;

        final FeatureAndMatchParameters featureAndMatchParameters =
                new FeatureAndMatchParameters(siftFeatureParameters,
                                              siftMatchParameters,
                                              null,
                                              null);

        final GeometricDescriptorParameters gdParameters = new GeometricDescriptorParameters();
        gdParameters.numberOfNeighbors = 3;
        gdParameters.redundancy = 1;
        gdParameters.significance = 2.0;
        gdParameters.sigma = 2.04;
        gdParameters.threshold = 0.008;
        gdParameters.localization = GeometricDescriptorParameters.LocalizationFitType.NONE;
        gdParameters.lookForMinima = true;
        gdParameters.lookForMaxima = false;
        gdParameters.fullScaleBlockRadius = 300.0;
        gdParameters.fullScaleNonMaxSuppressionRadius = 60.0;
        gdParameters.gdStoredMatchWeight = 0.4;

        final MatchDerivationParameters gdMatchParameters = new MatchDerivationParameters();
        gdMatchParameters.matchModelType = ModelType.RIGID;
        gdMatchParameters.matchIterations = 1000;
        gdMatchParameters.matchMaxEpsilon = 20.0f;
        gdMatchParameters.matchMinInlierRatio = 0.0f;
        gdMatchParameters.matchMinNumInliers = 4;
        gdMatchParameters.matchMaxTrust = 3.0;
        gdMatchParameters.matchFilter = FilterType.SINGLE_SET;

        final double gdRenderScale = 0.25;

        final GeometricDescriptorAndMatchFilterParameters gdAndMatchParameters =
                new GeometricDescriptorAndMatchFilterParameters(gdParameters,
                                                                gdMatchParameters,
                                                                gdRenderScale,
                                                                false,
                                                                null);

        final MatchTrialParameters trialParameters = new MatchTrialParameters(featureAndMatchParameters,
                                                                              pTileUrl,
                                                                              qTileUrl,
                                                                              gdAndMatchParameters);

        final JsonUtils.Helper<MatchTrialParameters> JSON_HELPER =
                new JsonUtils.Helper<>(MatchTrialParameters.class);

        System.out.println(JSON_HELPER.toJson(trialParameters));

        final MatchTrial matchTrial = new MatchTrial(trialParameters);
        matchTrial.deriveResults();

        System.out.println(matchTrial.toJson());
    }
}

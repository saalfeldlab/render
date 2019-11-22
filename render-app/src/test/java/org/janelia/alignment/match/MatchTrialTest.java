package org.janelia.alignment.match;

import java.io.StringReader;

import org.janelia.alignment.match.parameters.MatchTrialParameters;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link MatchTrial} class.
 *
 * @author Eric Trautman
 */
public class MatchTrialTest {

    @Test
    public void testConstructors() {
        final MatchTrial matchTrial = new MatchTrial();
        Assert.assertNull(matchTrial.getStats());

        final MatchTrialStats stats = new MatchTrialStats();
        Assert.assertNotNull(stats);
    }

    public static void main(final String[] args) {

        final String parametersJson = args.length == 0 ? CROSS_GD_TRIAL_A_JSON : MONTAGE_TRIAL_A_JSON;

        final MatchTrialParameters trialParameters = MatchTrialParameters.fromJson(new StringReader(parametersJson));

        final MatchTrial matchTrial = new MatchTrial(trialParameters);
        matchTrial.deriveResults();

        System.out.println(matchTrial.toJson());
    }

    private static final  String CROSS_GD_TRIAL_A_JSON =
            "{\n" +
            "    \"featureAndMatchParameters\" : {\n" +
            "      \"siftFeatureParameters\" : {\n" +
            "        \"fdSize\" : 4,\n" +
            "        \"minScale\" : 0.25,\n" +
            "        \"maxScale\" : 1.0,\n" +
            "        \"steps\" : 3\n" +
            "      },\n" +
            "      \"matchDerivationParameters\" : {\n" +
            "        \"matchRod\" : 0.92,\n" +
            "        \"matchModelType\" : \"AFFINE\",\n" +
            "        \"matchRegularizerModelType\" : \"RIGID\",\n" +
            "        \"matchInterpolatedModelLambda\" : 0.25,\n" +
            "        \"matchIterations\" : 1000,\n" +
            "        \"matchMaxEpsilon\" : 50.0,\n" +
            "        \"matchMinInlierRatio\" : 0.0,\n" +
            "        \"matchMinNumInliers\" : 10,\n" +
            "        \"matchMaxTrust\" : 4.0,\n" +
            "        \"matchFilter\" : \"SINGLE_SET\",\n" +
            "        \"matchFullScaleCoverageRadius\" : 300.0\n" +
            "      }\n" +
            "    },\n" +
            "    \"pRenderParametersUrl\" : \"http://renderer-dev.int.janelia.org:8080/render-ws/v1/owner/Z1217_19m/project/Sec07/stack/v1_acquire/tile/19-02-21_104336_0-0-0.26091.0/render-parameters?filter=true&scale=0.15\",\n" +
            "    \"qRenderParametersUrl\" : \"http://renderer-dev.int.janelia.org:8080/render-ws/v1/owner/Z1217_19m/project/Sec07/stack/v1_acquire/tile/19-02-21_104444_0-0-0.26092.0/render-parameters?filter=true&scale=0.15\",\n" +
            "    \"geometricDescriptorAndMatchFilterParameters\" : {\n" +
            "      \"renderScale\" : 0.1,\n" +
            "      \"renderWithFilter\" : false,\n" +
            "      \"geometricDescriptorParameters\" : {\n" +
            "        \"numberOfNeighbors\" : 3,\n" +
            "        \"redundancy\" : 1,\n" +
            "        \"significance\" : 2.0,\n" +
            "        \"sigma\" : 2.04,\n" +
            "        \"threshold\" : 0.008,\n" +
            "        \"localization\" : \"NONE\",\n" +
            "        \"lookForMinima\" : true,\n" +
            "        \"lookForMaxima\" : false,\n" +
            "        \"similarOrientation\" : true,\n" +
            "        \"fullScaleBlockRadius\" : 300.0,\n" +
            "        \"fullScaleNonMaxSuppressionRadius\" : 60.0,\n" +
            "        \"gdStoredMatchWeight\" : 0.4\n" +
            "      },\n" +
            "      \"matchDerivationParameters\" : {\n" +
            "        \"matchRod\" : 0.92,\n" +
            "        \"matchModelType\" : \"RIGID\",\n" +
            "        \"matchIterations\" : 1000,\n" +
            "        \"matchMaxEpsilon\" : 20.0,\n" +
            "        \"matchMinInlierRatio\" : 0.0,\n" +
            "        \"matchMinNumInliers\" : 4,\n" +
            "        \"matchMaxTrust\" : 3.0,\n" +
            "        \"matchFilter\" : \"SINGLE_SET\",\n" +
            "        \"matchFullScaleCoverageRadius\" : 300.0\n" +
            "      }\n" +
            "    }\n" +
            "  }";

    private static final String MONTAGE_TRIAL_A_JSON =
            "{\n" +
            "  \"featureAndMatchParameters\": {\n" +
            "    \"siftFeatureParameters\": {\n" +
            "      \"fdSize\": 4,\n" +
            "      \"minScale\": 0.25,\n" +
            "      \"maxScale\": 1,\n" +
            "      \"steps\": 5\n" +
            "      },\n" +
            "    \"matchDerivationParameters\": {\n" +
            "      \"matchRod\": 0.92,\n" +
            "      \"matchModelType\": \"RIGID\",\n" +
            "      \"matchRegularizerModelType\": \"TRANSLATION\",\n" +
            "      \"matchInterpolatedModelLambda\": 0.25,\n" +
            "      \"matchIterations\": 1000,\n" +
            "      \"matchMaxEpsilon\": 30,\n" +
            "      \"matchMinInlierRatio\": 0,\n" +
            "      \"matchMinNumInliers\": 10,\n" +
            "      \"matchMaxTrust\": 4,\n" +
            "      \"matchFilter\": \"SINGLE_SET\",\n" +
            "      \"matchFullScaleCoverageRadius\": 300\n" +
            "    },\n" +
            "    \"pClipPosition\": \"LEFT\",\n" +
            "    \"clipPixels\": 500\n" +
            "  },\n" +
            "  \"pRenderParametersUrl\": \"http://renderer-dev.int.janelia.org:8080/render-ws/v1/owner/Z1217_19m/project/Sec07/stack/v1_acquire/tile/19-02-07_212459_0-0-1.10001.0/render-parameters?filter=true&scale=0.3\",\n" +
            "  \"qRenderParametersUrl\": \"http://renderer-dev.int.janelia.org:8080/render-ws/v1/owner/Z1217_19m/project/Sec07/stack/v1_acquire/tile/19-02-07_212459_0-0-2.10001.0/render-parameters?filter=true&scale=0.3\"\n" +
            "}";

}

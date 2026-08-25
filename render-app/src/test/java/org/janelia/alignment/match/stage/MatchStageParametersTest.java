package org.janelia.alignment.match.stage;

import java.io.StringReader;
import java.util.List;

import org.janelia.alignment.match.parameters.MatchStageParameters;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the {@link StageMatchingResources} class.
 *
 * @author Eric Trautman
 */
public class MatchStageParametersTest {

    @Test
    public void testToSlug() {

        final List<MatchStageParameters> stageParametersList =
                MatchStageParameters.fromJsonArray(new StringReader(CROSS_JSON));

        assertEquals(6, stageParametersList.size(),
                     "invalid number of stage parameters loaded");

        MatchStageParameters p = stageParametersList.get(2);
        String expectedSlug = "crossPass3_SIFT_s0.15e09_i020_c050pct_" +
                              "GEO_s0.25e04_i150_c050pct_" +
                              "d006_" +
                              "h1588887f682e680539e3654936b5dac3";
        assertEquals(expectedSlug, p.toSlug(),
                     "invalid slug for " + p.getStageName());

        p = stageParametersList.get(3);
        expectedSlug = "crossPass4_SIFT_s0.25e15_i150_c030pct_" +
                       "GEO_none----_----_-------_" +
                       "d003_" +
                       "h94f34f08766629b82aa6c28506897514";
        assertEquals(expectedSlug, p.toSlug(),
                     "invalid slug for " + p.getStageName());
    }

    @Test
    public void testMissingLambda() {

        final List<MatchStageParameters> stageParametersList =
                MatchStageParameters.fromJsonArray(new StringReader(MISSING_LAMBDA_JSON));

        assertEquals(1, stageParametersList.size(),
                     "invalid number of stage parameters loaded");
        assertThrows(IllegalArgumentException.class, () -> stageParametersList.getFirst().validateAndSetDefaults());
    }

    private static final String CROSS_JSON = """
                    [
                      {
                        "featureExtraction": {
                          "fdSize": 4,
                          "maxScale": 1.0,
                          "minScale": 0.125,
                          "steps": 5
                        },
                        "featureMatchDerivation": {
                          "matchFilter": "SINGLE_SET",
                          "matchFullScaleCoverageRadius": 300.0,
                          "matchIterations": 1000,
                          "matchMaxEpsilon": 3.0,
                          "matchMaxTrust": 4.0,
                          "matchMinCoveragePercentage": 50.0,
                          "matchMinInlierRatio": 0.0,
                          "matchMinNumInliers": 20,
                          "matchModelType": "RIGID",
                          "matchRod": 0.92
                        },
                        "featureRender": {
                          "renderScale": 0.05,
                          "renderWithFilter": true,
                          "renderWithoutMask": false
                        },
                        "geometricDescriptorAndMatch": {
                          "gdEnabled": true,
                          "geometricDescriptorParameters": {
                            "fullScaleBlockRadius": 500.0,
                            "fullScaleNonMaxSuppressionRadius": 100.0,
                            "gdStoredMatchWeight": 0.4,
                            "localization": "THREE_D_QUADRATIC",
                            "lookForMinima": true,
                            "numberOfNeighbors": 3,
                            "redundancy": 1,
                            "sigma": 2.04,
                            "significance": 2.0,
                            "similarOrientation": true,
                            "threshold": 0.006
                          },
                          "matchDerivationParameters": {
                            "matchFilter": "SINGLE_SET",
                            "matchFullScaleCoverageRadius": 300.0,
                            "matchIterations": 1000,
                            "matchMaxEpsilon": 2.0,
                            "matchMaxTrust": 3.0,
                            "matchMinInlierRatio": 0.0,
                            "matchMinNumInliers": 20,
                            "matchModelType": "RIGID"
                          },
                          "minCombinedCoveragePercentage": 50.0,
                          "minCombinedInliers": 150,
                          "renderScale": 0.1,
                          "renderWithFilter": false
                        },
                        "maxNeighborDistance": 6,
                        "stageName": "crossPass1"
                      },
                      {
                        "featureExtraction": {
                          "fdSize": 4,
                          "maxScale": 1.0,
                          "minScale": 0.125,
                          "steps": 5
                        },
                        "featureMatchDerivation": {
                          "matchFilter": "SINGLE_SET",
                          "matchFullScaleCoverageRadius": 300.0,
                          "matchIterations": 1000,
                          "matchMaxEpsilon": 6.0,
                          "matchMaxTrust": 4.0,
                          "matchMinCoveragePercentage": 50.0,
                          "matchMinInlierRatio": 0.0,
                          "matchMinNumInliers": 20,
                          "matchModelType": "RIGID",
                          "matchRod": 0.92
                        },
                        "featureRender": {
                          "renderScale": 0.1,
                          "renderWithFilter": true,
                          "renderWithoutMask": false
                        },
                        "geometricDescriptorAndMatch": {
                          "gdEnabled": true,
                          "geometricDescriptorParameters": {
                            "fullScaleBlockRadius": 500.0,
                            "fullScaleNonMaxSuppressionRadius": 100.0,
                            "gdStoredMatchWeight": 0.39,
                            "localization": "THREE_D_QUADRATIC",
                            "lookForMinima": true,
                            "numberOfNeighbors": 3,
                            "redundancy": 1,
                            "sigma": 2.04,
                            "significance": 2.0,
                            "similarOrientation": true,
                            "threshold": 0.006
                          },
                          "matchDerivationParameters": {
                            "matchFilter": "SINGLE_SET",
                            "matchFullScaleCoverageRadius": 300.0,
                            "matchIterations": 1000,
                            "matchMaxEpsilon": 3.0,
                            "matchMaxTrust": 3.0,
                            "matchMinInlierRatio": 0.0,
                            "matchMinNumInliers": 20,
                            "matchModelType": "RIGID"
                          },
                          "minCombinedCoveragePercentage": 50.0,
                          "minCombinedInliers": 150,
                          "renderScale": 0.15,
                          "renderWithFilter": false
                        },
                        "maxNeighborDistance": 6,
                        "stageName": "crossPass2"
                      },
                      {
                        "featureExtraction": {
                          "fdSize": 4,
                          "maxScale": 1.0,
                          "minScale": 0.125,
                          "steps": 5
                        },
                        "featureMatchDerivation": {
                          "matchFilter": "SINGLE_SET",
                          "matchFullScaleCoverageRadius": 300.0,
                          "matchIterations": 1000,
                          "matchMaxEpsilon": 9.0,
                          "matchMaxTrust": 4.0,
                          "matchMinCoveragePercentage": 50.0,
                          "matchMinInlierRatio": 0.0,
                          "matchMinNumInliers": 20,
                          "matchModelType": "RIGID",
                          "matchRod": 0.92
                        },
                        "featureRender": {
                          "renderScale": 0.15,
                          "renderWithFilter": true,
                          "renderWithoutMask": false
                        },
                        "geometricDescriptorAndMatch": {
                          "gdEnabled": true,
                          "geometricDescriptorParameters": {
                            "fullScaleBlockRadius": 500.0,
                            "fullScaleNonMaxSuppressionRadius": 100.0,
                            "gdStoredMatchWeight": 0.38,
                            "localization": "THREE_D_QUADRATIC",
                            "lookForMinima": true,
                            "numberOfNeighbors": 3,
                            "redundancy": 1,
                            "sigma": 2.04,
                            "significance": 2.0,
                            "similarOrientation": true,
                            "threshold": 0.006
                          },
                          "matchDerivationParameters": {
                            "matchFilter": "SINGLE_SET",
                            "matchFullScaleCoverageRadius": 300.0,
                            "matchIterations": 1000,
                            "matchMaxEpsilon": 4.0,
                            "matchMaxTrust": 3.0,
                            "matchMinInlierRatio": 0.0,
                            "matchMinNumInliers": 20,
                            "matchModelType": "RIGID"
                          },
                          "minCombinedCoveragePercentage": 50.0,
                          "minCombinedInliers": 150,
                          "renderScale": 0.25,
                          "renderWithFilter": false
                        },
                        "maxNeighborDistance": 6,
                        "stageName": "crossPass3"
                      },
                      {
                        "featureExtraction": {
                          "fdSize": 4,
                          "maxScale": 1.0,
                          "minScale": 0.125,
                          "steps": 5
                        },
                        "featureMatchDerivation": {
                          "matchFilter": "SINGLE_SET",
                          "matchFullScaleCoverageRadius": 300.0,
                          "matchIterations": 1000,
                          "matchMaxEpsilon": 15.0,
                          "matchMaxTrust": 4.0,
                          "matchMinCoveragePercentage": 30.0,
                          "matchMinInlierRatio": 0.0,
                          "matchMinNumInliers": 150,
                          "matchModelType": "RIGID",
                          "matchRod": 0.92
                        },
                        "featureRender": {
                          "renderScale": 0.25,
                          "renderWithFilter": true,
                          "renderWithoutMask": false
                        },
                        "geometricDescriptorAndMatch": {
                          "gdEnabled": false
                        },
                        "maxNeighborDistance": 3,
                        "stageName": "crossPass4"
                      },
                      {
                        "featureExtraction": {
                          "fdSize": 4,
                          "maxScale": 1.0,
                          "minScale": 0.125,
                          "steps": 5
                        },
                        "featureMatchDerivation": {
                          "matchFilter": "SINGLE_SET",
                          "matchFullScaleCoverageRadius": 300.0,
                          "matchIterations": 1000,
                          "matchMaxEpsilon": 15.0,
                          "matchMaxTrust": 4.0,
                          "matchMinCoveragePercentage": 50.0,
                          "matchMinInlierRatio": 0.0,
                          "matchMinNumInliers": 20,
                          "matchModelType": "RIGID",
                          "matchRod": 0.92
                        },
                        "featureRender": {
                          "renderScale": 0.25,
                          "renderWithFilter": true,
                          "renderWithoutMask": false
                        },
                        "geometricDescriptorAndMatch": {
                          "gdEnabled": true,
                          "geometricDescriptorParameters": {
                            "fullScaleBlockRadius": 500.0,
                            "fullScaleNonMaxSuppressionRadius": 50.0,
                            "gdStoredMatchWeight": 0.37,
                            "localization": "THREE_D_QUADRATIC",
                            "lookForMinima": true,
                            "numberOfNeighbors": 3,
                            "redundancy": 1,
                            "sigma": 4.04,
                            "significance": 2.0,
                            "similarOrientation": true,
                            "threshold": 0.006
                          },
                          "matchDerivationParameters": {
                            "matchFilter": "SINGLE_SET",
                            "matchFullScaleCoverageRadius": 300.0,
                            "matchIterations": 1000,
                            "matchMaxEpsilon": 8.0,
                            "matchMaxTrust": 3.0,
                            "matchMinInlierRatio": 0.0,
                            "matchMinNumInliers": 20,
                            "matchModelType": "RIGID"
                          },
                          "minCombinedCoveragePercentage": 0.0,
                          "minCombinedInliers": 75,
                          "renderScale": 0.5,
                          "renderWithFilter": false,
                          "runGeoRegardlessOfSiftResults": true
                        },
                        "maxNeighborDistance": 2,
                        "stageName": "crossPass5"
                      },
                      {
                        "featureExtraction": {
                          "fdSize": 4,
                          "maxScale": 1.0,
                          "minScale": 0.125,
                          "steps": 5
                        },
                        "featureMatchDerivation": {
                          "matchFilter": "SINGLE_SET",
                          "matchFullScaleCoverageRadius": 300.0,
                          "matchIterations": 1000,
                          "matchMaxEpsilon": 5.0,
                          "matchMaxTrust": 4.0,
                          "matchMinInlierRatio": 0.0,
                          "matchMinNumInliers": 20,
                          "matchModelType": "RIGID",
                          "matchRod": 0.92
                        },
                        "featureRender": {
                          "renderScale": 0.25,
                          "renderWithFilter": true,
                          "renderWithoutMask": false
                        },
                        "geometricDescriptorAndMatch": {
                          "gdEnabled": false
                        },
                        "maxNeighborDistance": 1,
                        "stageName": "crossPass6"
                      }
                    ]""";

    private static final String MISSING_LAMBDA_JSON =
            "[\n" +
            "  {\n" +
            "    \"featureExtraction\": {\n" +
            "      \"fdSize\": 4,\n" +
            "      \"maxScale\": 1.0,\n" +
            "      \"minScale\": 0.125,\n" +
            "      \"steps\": 5\n" +
            "    },\n" +
            "    \"featureMatchDerivation\": {\n" +
            "      \"matchFilter\": \"SINGLE_SET\",\n" +
            "      \"matchFullScaleCoverageRadius\": 300.0,\n" +
//            "      \"matchInterpolatedModelLambda\": 0.25,\n" +
            "      \"matchIterations\": 1000,\n" +
            "      \"matchMaxEpsilon\": 3.0,\n" +
            "      \"matchMaxTrust\": 4.0,\n" +
            "      \"matchMinCoveragePercentage\": 50.0,\n" +
            "      \"matchMinInlierRatio\": 0.0,\n" +
            "      \"matchMinNumInliers\": 20,\n" +
            "      \"matchModelType\": \"RIGID\",\n" +
            "      \"matchRegularizerModelType\": \"TRANSLATION\",\n" +
            "      \"matchRod\": 0.92\n" +
            "    },\n" +
            "    \"featureRender\": {\n" +
            "      \"renderScale\": 0.05,\n" +
            "      \"renderWithFilter\": true,\n" +
            "      \"renderWithoutMask\": false\n" +
            "    },\n" +
            "    \"geometricDescriptorAndMatch\": {\n" +
            "      \"gdEnabled\": false\n" +
            "    },\n" +
            "    \"maxNeighborDistance\": 6,\n" +
            "    \"stageName\": \"missingLambda\"\n" +
            "  }\n" +
            "]";

}
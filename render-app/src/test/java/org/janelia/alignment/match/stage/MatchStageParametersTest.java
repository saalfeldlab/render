package org.janelia.alignment.match.stage;

import java.io.StringReader;
import java.util.List;

import org.janelia.alignment.match.parameters.MatchStageParameters;
import org.junit.Assert;
import org.junit.Test;

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

        Assert.assertEquals("invalid number of stage parameters loaded",
                            6, stageParametersList.size());

        MatchStageParameters p = stageParametersList.get(2);
        String expectedSlug = "crossPass3_SIFT_s0.15e09_i020_c050pct_" +
                              "GEO_s0.25e04_i150_c050pct_" +
                              "d006_" +
                              "h1588887f682e680539e3654936b5dac3";
        Assert.assertEquals("invalid slug for " + p.getStageName(),
                            expectedSlug, p.toSlug());

        p = stageParametersList.get(3);
        expectedSlug = "crossPass4_SIFT_s0.25e15_i150_c030pct_" +
                       "GEO_none----_----_-------_" +
                       "d003_" +
                       "hf28a03394b65a541a6057ee3954b6845";
        Assert.assertEquals("invalid slug for " + p.getStageName(),
                            expectedSlug, p.toSlug());
    }

    private static final String CROSS_JSON =
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
            "      \"matchIterations\": 1000,\n" +
            "      \"matchMaxEpsilon\": 3.0,\n" +
            "      \"matchMaxTrust\": 4.0,\n" +
            "      \"matchMinCoveragePercentage\": 50.0,\n" +
            "      \"matchMinInlierRatio\": 0.0,\n" +
            "      \"matchMinNumInliers\": 20,\n" +
            "      \"matchModelType\": \"RIGID\",\n" +
            "      \"matchRod\": 0.92\n" +
            "    },\n" +
            "    \"featureRender\": {\n" +
            "      \"renderScale\": 0.05,\n" +
            "      \"renderWithFilter\": true,\n" +
            "      \"renderWithoutMask\": false\n" +
            "    },\n" +
            "    \"geometricDescriptorAndMatch\": {\n" +
            "      \"gdEnabled\": true,\n" +
            "      \"geometricDescriptorParameters\": {\n" +
            "        \"fullScaleBlockRadius\": 500.0,\n" +
            "        \"fullScaleNonMaxSuppressionRadius\": 100.0,\n" +
            "        \"gdStoredMatchWeight\": 0.4,\n" +
            "        \"localization\": \"THREE_D_QUADRATIC\",\n" +
            "        \"lookForMinima\": true,\n" +
            "        \"numberOfNeighbors\": 3,\n" +
            "        \"redundancy\": 1,\n" +
            "        \"sigma\": 2.04,\n" +
            "        \"significance\": 2.0,\n" +
            "        \"similarOrientation\": true,\n" +
            "        \"threshold\": 0.006\n" +
            "      },\n" +
            "      \"matchDerivationParameters\": {\n" +
            "        \"matchFilter\": \"SINGLE_SET\",\n" +
            "        \"matchFullScaleCoverageRadius\": 300.0,\n" +
            "        \"matchIterations\": 1000,\n" +
            "        \"matchMaxEpsilon\": 2.0,\n" +
            "        \"matchMaxTrust\": 3.0,\n" +
            "        \"matchMinInlierRatio\": 0.0,\n" +
            "        \"matchMinNumInliers\": 20,\n" +
            "        \"matchModelType\": \"RIGID\"\n" +
            "      },\n" +
            "      \"minCombinedCoveragePercentage\": 50.0,\n" +
            "      \"minCombinedInliers\": 150,\n" +
            "      \"renderScale\": 0.1,\n" +
            "      \"renderWithFilter\": false\n" +
            "    },\n" +
            "    \"maxNeighborDistance\": 6,\n" +
            "    \"stageName\": \"crossPass1\"\n" +
            "  },\n" +
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
            "      \"matchIterations\": 1000,\n" +
            "      \"matchMaxEpsilon\": 6.0,\n" +
            "      \"matchMaxTrust\": 4.0,\n" +
            "      \"matchMinCoveragePercentage\": 50.0,\n" +
            "      \"matchMinInlierRatio\": 0.0,\n" +
            "      \"matchMinNumInliers\": 20,\n" +
            "      \"matchModelType\": \"RIGID\",\n" +
            "      \"matchRod\": 0.92\n" +
            "    },\n" +
            "    \"featureRender\": {\n" +
            "      \"renderScale\": 0.1,\n" +
            "      \"renderWithFilter\": true,\n" +
            "      \"renderWithoutMask\": false\n" +
            "    },\n" +
            "    \"geometricDescriptorAndMatch\": {\n" +
            "      \"gdEnabled\": true,\n" +
            "      \"geometricDescriptorParameters\": {\n" +
            "        \"fullScaleBlockRadius\": 500.0,\n" +
            "        \"fullScaleNonMaxSuppressionRadius\": 100.0,\n" +
            "        \"gdStoredMatchWeight\": 0.39,\n" +
            "        \"localization\": \"THREE_D_QUADRATIC\",\n" +
            "        \"lookForMinima\": true,\n" +
            "        \"numberOfNeighbors\": 3,\n" +
            "        \"redundancy\": 1,\n" +
            "        \"sigma\": 2.04,\n" +
            "        \"significance\": 2.0,\n" +
            "        \"similarOrientation\": true,\n" +
            "        \"threshold\": 0.006\n" +
            "      },\n" +
            "      \"matchDerivationParameters\": {\n" +
            "        \"matchFilter\": \"SINGLE_SET\",\n" +
            "        \"matchFullScaleCoverageRadius\": 300.0,\n" +
            "        \"matchIterations\": 1000,\n" +
            "        \"matchMaxEpsilon\": 3.0,\n" +
            "        \"matchMaxTrust\": 3.0,\n" +
            "        \"matchMinInlierRatio\": 0.0,\n" +
            "        \"matchMinNumInliers\": 20,\n" +
            "        \"matchModelType\": \"RIGID\"\n" +
            "      },\n" +
            "      \"minCombinedCoveragePercentage\": 50.0,\n" +
            "      \"minCombinedInliers\": 150,\n" +
            "      \"renderScale\": 0.15,\n" +
            "      \"renderWithFilter\": false\n" +
            "    },\n" +
            "    \"maxNeighborDistance\": 6,\n" +
            "    \"stageName\": \"crossPass2\"\n" +
            "  },\n" +
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
            "      \"matchIterations\": 1000,\n" +
            "      \"matchMaxEpsilon\": 9.0,\n" +
            "      \"matchMaxTrust\": 4.0,\n" +
            "      \"matchMinCoveragePercentage\": 50.0,\n" +
            "      \"matchMinInlierRatio\": 0.0,\n" +
            "      \"matchMinNumInliers\": 20,\n" +
            "      \"matchModelType\": \"RIGID\",\n" +
            "      \"matchRod\": 0.92\n" +
            "    },\n" +
            "    \"featureRender\": {\n" +
            "      \"renderScale\": 0.15,\n" +
            "      \"renderWithFilter\": true,\n" +
            "      \"renderWithoutMask\": false\n" +
            "    },\n" +
            "    \"geometricDescriptorAndMatch\": {\n" +
            "      \"gdEnabled\": true,\n" +
            "      \"geometricDescriptorParameters\": {\n" +
            "        \"fullScaleBlockRadius\": 500.0,\n" +
            "        \"fullScaleNonMaxSuppressionRadius\": 100.0,\n" +
            "        \"gdStoredMatchWeight\": 0.38,\n" +
            "        \"localization\": \"THREE_D_QUADRATIC\",\n" +
            "        \"lookForMinima\": true,\n" +
            "        \"numberOfNeighbors\": 3,\n" +
            "        \"redundancy\": 1,\n" +
            "        \"sigma\": 2.04,\n" +
            "        \"significance\": 2.0,\n" +
            "        \"similarOrientation\": true,\n" +
            "        \"threshold\": 0.006\n" +
            "      },\n" +
            "      \"matchDerivationParameters\": {\n" +
            "        \"matchFilter\": \"SINGLE_SET\",\n" +
            "        \"matchFullScaleCoverageRadius\": 300.0,\n" +
            "        \"matchIterations\": 1000,\n" +
            "        \"matchMaxEpsilon\": 4.0,\n" +
            "        \"matchMaxTrust\": 3.0,\n" +
            "        \"matchMinInlierRatio\": 0.0,\n" +
            "        \"matchMinNumInliers\": 20,\n" +
            "        \"matchModelType\": \"RIGID\"\n" +
            "      },\n" +
            "      \"minCombinedCoveragePercentage\": 50.0,\n" +
            "      \"minCombinedInliers\": 150,\n" +
            "      \"renderScale\": 0.25,\n" +
            "      \"renderWithFilter\": false\n" +
            "    },\n" +
            "    \"maxNeighborDistance\": 6,\n" +
            "    \"stageName\": \"crossPass3\"\n" +
            "  },\n" +
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
            "      \"matchIterations\": 1000,\n" +
            "      \"matchMaxEpsilon\": 15.0,\n" +
            "      \"matchMaxTrust\": 4.0,\n" +
            "      \"matchMinCoveragePercentage\": 30.0,\n" +
            "      \"matchMinInlierRatio\": 0.0,\n" +
            "      \"matchMinNumInliers\": 150,\n" +
            "      \"matchModelType\": \"RIGID\",\n" +
            "      \"matchRod\": 0.92\n" +
            "    },\n" +
            "    \"featureRender\": {\n" +
            "      \"renderScale\": 0.25,\n" +
            "      \"renderWithFilter\": true,\n" +
            "      \"renderWithoutMask\": false\n" +
            "    },\n" +
            "    \"geometricDescriptorAndMatch\": {\n" +
            "      \"gdEnabled\": false\n" +
            "    },\n" +
            "    \"maxNeighborDistance\": 3,\n" +
            "    \"stageName\": \"crossPass4\"\n" +
            "  },\n" +
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
            "      \"matchIterations\": 1000,\n" +
            "      \"matchMaxEpsilon\": 15.0,\n" +
            "      \"matchMaxTrust\": 4.0,\n" +
            "      \"matchMinCoveragePercentage\": 50.0,\n" +
            "      \"matchMinInlierRatio\": 0.0,\n" +
            "      \"matchMinNumInliers\": 20,\n" +
            "      \"matchModelType\": \"RIGID\",\n" +
            "      \"matchRod\": 0.92\n" +
            "    },\n" +
            "    \"featureRender\": {\n" +
            "      \"renderScale\": 0.25,\n" +
            "      \"renderWithFilter\": true,\n" +
            "      \"renderWithoutMask\": false\n" +
            "    },\n" +
            "    \"geometricDescriptorAndMatch\": {\n" +
            "      \"gdEnabled\": true,\n" +
            "      \"geometricDescriptorParameters\": {\n" +
            "        \"fullScaleBlockRadius\": 500.0,\n" +
            "        \"fullScaleNonMaxSuppressionRadius\": 50.0,\n" +
            "        \"gdStoredMatchWeight\": 0.37,\n" +
            "        \"localization\": \"THREE_D_QUADRATIC\",\n" +
            "        \"lookForMinima\": true,\n" +
            "        \"numberOfNeighbors\": 3,\n" +
            "        \"redundancy\": 1,\n" +
            "        \"sigma\": 4.04,\n" +
            "        \"significance\": 2.0,\n" +
            "        \"similarOrientation\": true,\n" +
            "        \"threshold\": 0.006\n" +
            "      },\n" +
            "      \"matchDerivationParameters\": {\n" +
            "        \"matchFilter\": \"SINGLE_SET\",\n" +
            "        \"matchFullScaleCoverageRadius\": 300.0,\n" +
            "        \"matchIterations\": 1000,\n" +
            "        \"matchMaxEpsilon\": 8.0,\n" +
            "        \"matchMaxTrust\": 3.0,\n" +
            "        \"matchMinInlierRatio\": 0.0,\n" +
            "        \"matchMinNumInliers\": 20,\n" +
            "        \"matchModelType\": \"RIGID\"\n" +
            "      },\n" +
            "      \"minCombinedCoveragePercentage\": 0.0,\n" +
            "      \"minCombinedInliers\": 75,\n" +
            "      \"renderScale\": 0.5,\n" +
            "      \"renderWithFilter\": false,\n" +
            "      \"runGeoRegardlessOfSiftResults\": true\n" +
            "    },\n" +
            "    \"maxNeighborDistance\": 2,\n" +
            "    \"stageName\": \"crossPass5\"\n" +
            "  },\n" +
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
            "      \"matchIterations\": 1000,\n" +
            "      \"matchMaxEpsilon\": 5.0,\n" +
            "      \"matchMaxTrust\": 4.0,\n" +
            "      \"matchMinInlierRatio\": 0.0,\n" +
            "      \"matchMinNumInliers\": 20,\n" +
            "      \"matchModelType\": \"RIGID\",\n" +
            "      \"matchRod\": 0.92\n" +
            "    },\n" +
            "    \"featureRender\": {\n" +
            "      \"renderScale\": 0.25,\n" +
            "      \"renderWithFilter\": true,\n" +
            "      \"renderWithoutMask\": false\n" +
            "    },\n" +
            "    \"geometricDescriptorAndMatch\": {\n" +
            "      \"gdEnabled\": false\n" +
            "    },\n" +
            "    \"maxNeighborDistance\": 1,\n" +
            "    \"stageName\": \"crossPass6\"\n" +
            "  }\n" +
            "]";
}
package org.janelia.render.client;

import java.io.StringReader;
import java.util.Collections;
import java.util.List;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.MontageRelativePosition;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.janelia.alignment.match.parameters.FeatureAndMatchParameters;
import org.janelia.alignment.match.parameters.MatchTrialParameters;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link SIFTPointMatchClient} class.
 *
 * @author Eric Trautman
 */
public class SIFTPointMatchClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new SIFTPointMatchClient.Parameters());
    }

    // --------------------------------------------------------------
    // The following methods support ad-hoc interactive testing with external render web services.
    // Consequently, they aren't included in the unit test suite.

    public static void main(final String[] args) {
        try {

            final int defaultTestIndex = 3;
            final int testIndex = args.length == 0 ? defaultTestIndex : Integer.parseInt(args[0]);

            runTestWithExternalDependencies(testIndex);
            
        } catch (final Throwable t) {
            t.printStackTrace();
        }
    }

    private static void runTestWithExternalDependencies(final int testIndex)
            throws Exception {

        final String owner = "Z1217_19m";
        final String project = "Sec07";
        final String stack = "v1_acquire";

        final String urlTemplateString = "{baseDataUrl}/owner/" + owner + "/project/" + project + "/stack/" + stack +
                                         "/tile/{id}/render-parameters";
        final CanvasId pCanvasId = getCanvasId(testIndex, true);
        final CanvasId qCanvasId = getCanvasId(testIndex, false);
        final OrderedCanvasIdPair pair = new OrderedCanvasIdPair(pCanvasId, qCanvasId, null);
        final List<OrderedCanvasIdPair> pairList = Collections.singletonList(pair);
        final RenderableCanvasIdPairs renderableCanvasIdPairs = new RenderableCanvasIdPairs(urlTemplateString,
                                                                                            pairList);

        final SIFTPointMatchClient.Parameters parameters = getParameters(testIndex);

        // must manually set peak cache size since that is a command line only parameter
        parameters.maxPeakCacheGb = 1;

        // HACK: set up match client with invalid collection name so that nothing actually gets saved
        parameters.matchClient.baseDataUrl = "http://renderer-dev.int.janelia.org:8080/render-ws/v1";
        parameters.matchClient.owner = owner;
        parameters.matchClient.collection = "##_invalid_collection_name_##";

        final SIFTPointMatchClient client = new SIFTPointMatchClient(parameters);

        client.generateMatchesForPairs(renderableCanvasIdPairs,
                                       parameters.matchClient.baseDataUrl,
                                       parameters.featureRender,
                                       parameters.featureRenderClip,
                                       parameters.featureExtraction,
                                       parameters.featureStorage,
                                       parameters.matchDerivation);
    }

    private static CanvasId getCanvasId(final int testIndex,
                                 final boolean forP) {
        final int offset = forP ? 0 : 3;
        final String positionString = TEST_PAIR_IDS[testIndex][2 + offset];
        final MontageRelativePosition relativePosition =
                positionString == null ? null : MontageRelativePosition.valueOf(positionString);
        return new CanvasId(TEST_PAIR_IDS[testIndex][offset], TEST_PAIR_IDS[testIndex][1 + offset], relativePosition);
    }

    private static SIFTPointMatchClient.Parameters getParameters(final int testIndex) {

        final SIFTPointMatchClient.Parameters clientParameters;

        final String json = TEST_PARAMETERS_JSON[testIndex];

        if (json.contains("matchClient")) {

            clientParameters = JSON_HELPER.fromJson(json);

        } else {

            final MatchTrialParameters matchTrialParameters = MatchTrialParameters.fromJson(new StringReader(json));

            final RenderParameters pRenderParameters =
                    RenderParameters.loadFromUrl(matchTrialParameters.getpRenderParametersUrl());

            clientParameters = new SIFTPointMatchClient.Parameters();

            // clientParameters.matchClient gets overwritten, so don't worry about it here

            clientParameters.featureRender.renderScale = pRenderParameters.getScale();
            clientParameters.featureRender.renderWithFilter = pRenderParameters.doFilter;
            clientParameters.featureRender.renderWithoutMask = pRenderParameters.excludeMask;
            clientParameters.featureRender.maskMinX = pRenderParameters.maskMinX;
            clientParameters.featureRender.maskMinY = pRenderParameters.maskMinY;

            final FeatureAndMatchParameters featureAndMatchParameters =
                    matchTrialParameters.getFeatureAndMatchParameters();
            clientParameters.featureRenderClip = featureAndMatchParameters.getClipParameters();
            clientParameters.featureExtraction = featureAndMatchParameters.getSiftFeatureParameters();
            // default clientParameters.featureStorage are fine
            clientParameters.matchDerivation = featureAndMatchParameters.getMatchDerivationParameters();

            if (matchTrialParameters.hasGeometricDescriptorAndMatchFilterParameters()) {
                clientParameters.geometricDescriptorAndMatch =
                        matchTrialParameters.getGeometricDescriptorAndMatchFilterParameters();
                clientParameters.geometricDescriptorAndMatch.gdEnabled = true;
            }

            // clientParameters.geometricDescriptorAndMatch.minCombinedInliers = 25;
            // clientParameters.geometricDescriptorAndMatch.minCombinedCoveragePercentage = 70.0;
        }

        return clientParameters;
    }

    private static final String[][] TEST_PAIR_IDS = {
            // test 0 canvas ID info
            { "26101.0", "19-02-21_105501_0-0-0.26101.0", null, "26102.0", "19-02-21_161150_0-0-0.26102.0", null },

            // test 1 canvas ID info
            { "22718.0", "19-02-18_163401_0-0-0.22718.0", "LEFT", "22718.0", "19-02-18_163401_0-0-1.22718.0", "RIGHT" },

            // test 2 canvas ID info
            { "22718.0", "19-02-18_163401_0-0-0.22718.0", "LEFT", "22718.0", "19-02-18_163401_0-0-1.22718.0", "RIGHT" },

            // test 3 canvas ID info
            { "19996.0", "19-02-16_090916_0-0-0.19996.0", "LEFT", "19996.0", "19-02-16_090916_0-0-1.19996.0", "RIGHT" }
    };

    private static final String[] TEST_PARAMETERS_JSON = {

            // -----------------------------------------------------------------------------------------------
            // test 0 parameters:
            "{\n" +
            "  \"matchClient\" : {\n" +
            "    \"baseDataUrl\" : \"http://renderer-dev.int.janelia.org:8080/render-ws/v1\",\n" +
            "    \"owner\" : \"Z1217_19m\",\n" +
            "    \"collection\" : \"test_gd_matches_Sec07\"\n" +
            "  },\n" +
            "  \"featureRender\" : {\n" +
            "    \"renderScale\" : 0.15,\n" +
            "    \"renderWithFilter\" : true,\n" +
            "    \"renderWithoutMask\" : false\n" +
            "  },\n" +
            "  \"featureRenderClip\" : { },\n" +
            "  \"featureExtraction\" : {\n" +
            "    \"fdSize\" : 4,\n" +
            "    \"minScale\" : 0.25,\n" +
            "    \"maxScale\" : 1.0,\n" +
            "    \"steps\" : 3\n" +
            "  },\n" +
            "  \"featureStorage\" : {\n" +
            "    \"requireStoredFeatures\" : false,\n" +
            "    \"maxFeatureCacheGb\" : 2,\n" +
            "    \"maxFeatureSourceCacheGb\" : 2\n" +
            "  },\n" +
            "  \"matchDerivation\" : {\n" +
            "    \"matchRod\" : 0.92,\n" +
            "    \"matchModelType\" : \"AFFINE\",\n" +
            "    \"matchRegularizerModelType\" : \"RIGID\",\n" +
            "    \"matchInterpolatedModelLambda\" : 0.25,\n" +
            "    \"matchIterations\" : 1000,\n" +
            "    \"matchMaxEpsilon\" : 50.0,\n" +
            "    \"matchMinInlierRatio\" : 0.0,\n" +
            "    \"matchMinNumInliers\" : 10,\n" +
            "    \"matchMaxTrust\" : 4.0,\n" +
            "    \"matchFilter\" : \"SINGLE_SET\",\n" +
            "    \"matchFullScaleCoverageRadius\" : 300.0\n" +
            "  },\n" +
            "  \"geometricDescriptorAndMatch\" : {\n" +
            "    \"gdEnabled\" : true,\n" +
            "    \"renderScale\" : 0.25,\n" +
            "    \"geometricDescriptorParameters\" : {\n" +
            "      \"numberOfNeighbors\" : 3,\n" +
            "      \"redundancy\" : 1,\n" +
            "      \"significance\" : 2.0,\n" +
            "      \"sigma\" : 2.04,\n" +
            "      \"threshold\" : 0.008,\n" +
            "      \"localization\" : \"NONE\",\n" +
            "      \"lookForMinima\" : true,\n" +
            "      \"lookForMaxima\" : false,\n" +
            "      \"similarOrientation\" : true,\n" +
            "      \"fullScaleBlockRadius\" : 0.0,\n" +
            "      \"fullScaleNonMaxSuppressionRadius\" : 120.0,\n" +
            "      \"gdStoredMatchWeight\" : 0.4\n" +
            "    },\n" +
            "    \"matchDerivationParameters\" : {\n" +
            "      \"matchRod\" : 0.92,\n" +
            "      \"matchModelType\" : \"RIGID\",\n" +
            "      \"matchIterations\" : 1000,\n" +
            "      \"matchMaxEpsilon\" : 20.0,\n" +
            "      \"matchMinInlierRatio\" : 0.0,\n" +
            "      \"matchMinNumInliers\" : 4,\n" +
            "      \"matchMaxTrust\" : 3.0,\n" +
            "      \"matchFilter\" : \"SINGLE_SET\",\n" +
            "      \"matchFullScaleCoverageRadius\" : 300.0\n" +
            "    },\n" +
            "    \"minCombinedInliers\" : 600,\n" +
            "    \"minCombinedCoveragePercentage\" : 55.0\n" +
            "  }\n" +
            "}",

            // -----------------------------------------------------------------------------------------------
            // test 1 parameters
            "{\n" +
            "    \"featureAndMatchParameters\" : {\n" +
            "      \"siftFeatureParameters\" : {\n" +
            "        \"fdSize\" : 4,\n" +
            "        \"minScale\" : 0.25,\n" +
            "        \"maxScale\" : 1.0,\n" +
            "        \"steps\" : 5\n" +
            "      },\n" +
            "      \"matchDerivationParameters\" : {\n" +
            "        \"matchRod\" : 0.92,\n" +
            "        \"matchModelType\" : \"RIGID\",\n" +
            "        \"matchRegularizerModelType\" : \"TRANSLATION\",\n" +
            "        \"matchInterpolatedModelLambda\" : 0.25,\n" +
            "        \"matchIterations\" : 1000,\n" +
            "        \"matchMaxEpsilon\" : 60.0,\n" +
            "        \"matchMinInlierRatio\" : 0.0,\n" +
            "        \"matchMinNumInliers\" : 10,\n" +
            "        \"matchMaxTrust\" : 4.0,\n" +
            "        \"matchFilter\" : \"SINGLE_SET\",\n" +
            "        \"matchFullScaleCoverageRadius\" : 300.0\n" +
            "      },\n" +
            "      \"pClipPosition\" : \"LEFT\",\n" +
            "      \"clipPixels\" : 500\n" +
            "    },\n" +
            "    \"pRenderParametersUrl\" : \"http://renderer-dev.int.janelia.org:8080/render-ws/v1/owner/Z1217_19m/project/Sec07/stack/v1_acquire/tile/19-02-18_163401_0-0-0.22718.0/render-parameters?filter=true&scale=0.6\",\n" +
            "    \"qRenderParametersUrl\" : \"http://renderer-dev.int.janelia.org:8080/render-ws/v1/owner/Z1217_19m/project/Sec07/stack/v1_acquire/tile/19-02-18_163401_0-0-1.22718.0/render-parameters?filter=true&scale=0.6\",\n" +
            "    \"geometricDescriptorAndMatchFilterParameters\" : {\n" +
            "      \"gdEnabled\" : true,\n" +
            "      \"renderScale\" : 1.0,\n" +
            "      \"renderWithFilter\" : false,\n" +
            "      \"geometricDescriptorParameters\" : {\n" +
            "        \"numberOfNeighbors\" : 3,\n" +
            "        \"redundancy\" : 2,\n" +
            "        \"significance\" : 1.5,\n" +
            "        \"sigma\" : 6.04,\n" +
            "        \"threshold\" : 0.008,\n" +
            "        \"localization\" : \"THREE_D_QUADRATIC\",\n" +
            "        \"lookForMinima\" : true,\n" +
            "        \"lookForMaxima\" : false,\n" +
            "        \"similarOrientation\" : true,\n" +
            "        \"fullScaleBlockRadius\" : 100.0,\n" +
            "        \"fullScaleNonMaxSuppressionRadius\" : 20.0,\n" +
            "        \"gdStoredMatchWeight\" : 0.4\n" +
            "      },\n" +
            "      \"matchDerivationParameters\" : {\n" +
            "        \"matchRod\" : 0.92,\n" +
            "        \"matchModelType\" : \"RIGID\",\n" +
            "        \"matchRegularizerModelType\" : \"TRANSLATION\",\n" +
            "        \"matchInterpolatedModelLambda\" : 0.25,\n" +
            "        \"matchIterations\" : 10000,\n" +
            "        \"matchMaxEpsilon\" : 20.0,\n" +
            "        \"matchMinInlierRatio\" : 0.0,\n" +
            "        \"matchMinNumInliers\" : 10,\n" +
            "        \"matchMaxTrust\" : 3.0,\n" +
            "        \"matchFilter\" : \"SINGLE_SET\",\n" +
            "        \"matchFullScaleCoverageRadius\" : 300.0\n" +
            "      }\n" +
            "    }\n" +
            "  }",

            // -----------------------------------------------------------------------------------------------
            // test 2 parameters
            "{\n" +
            "  \"matchClient\" : {\n" +
            "    \"baseDataUrl\" : \"http://10.40.3.162:8080/render-ws/v1\",\n" +
            "    \"owner\" : \"Z1217_19m\",\n" +
            "    \"collection\" : \"gd_test_Sec07_v1\"\n" +
            "  },\n" +
            "  \"featureRender\" : {\n" +
            "    \"renderScale\" : 0.6,\n" +
            "    \"renderWithFilter\" : true,\n" +
            "    \"renderWithoutMask\" : false\n" +
            "  },\n" +
            "  \"featureRenderClip\" : {\n" +
            "    \"clipWidth\" : 500,\n" +
            "    \"clipHeight\" : 500\n" +
            "  },\n" +
            "  \"featureExtraction\" : {\n" +
            "    \"fdSize\" : 4,\n" +
            "    \"minScale\" : 0.25,\n" +
            "    \"maxScale\" : 1.0,\n" +
            "    \"steps\" : 5\n" +
            "  },\n" +
            "  \"featureStorage\" : {\n" +
            "    \"requireStoredFeatures\" : false,\n" +
            "    \"maxFeatureCacheGb\" : 6,\n" +
            "    \"maxFeatureSourceCacheGb\" : 6\n" +
            "  },\n" +
            "  \"matchDerivation\" : {\n" +
            "    \"matchRod\" : 0.92,\n" +
            "    \"matchModelType\" : \"RIGID\",\n" +
            "    \"matchRegularizerModelType\" : \"TRANSLATION\",\n" +
            "    \"matchInterpolatedModelLambda\" : 0.25,\n" +
            "    \"matchIterations\" : 1000,\n" +
            "    \"matchMaxEpsilon\" : 60.0,\n" +
            "    \"matchMinInlierRatio\" : 0.0,\n" +
            "    \"matchMinNumInliers\" : 10,\n" +
            "    \"matchMaxTrust\" : 4.0,\n" +
            "    \"matchFilter\" : \"SINGLE_SET\",\n" +
            "    \"matchFullScaleCoverageRadius\" : 300.0,\n" +
            "    \"matchMinCoveragePercentage\" : 70.0\n" +
            "  },\n" +
            "  \"geometricDescriptorAndMatch\" : {\n" +
            "    \"gdEnabled\" : true,\n" +
            "    \"renderScale\" : 1.0,\n" +
            "    \"renderWithFilter\" : false,\n" +
            "    \"geometricDescriptorParameters\" : {\n" +
            "      \"numberOfNeighbors\" : 3,\n" +
            "      \"redundancy\" : 2,\n" +
            "      \"significance\" : 1.5,\n" +
            "      \"sigma\" : 6.04,\n" +
            "      \"threshold\" : 0.008,\n" +
            "      \"localization\" : \"THREE_D_QUADRATIC\",\n" +
            "      \"lookForMinima\" : true,\n" +
            "      \"lookForMaxima\" : false,\n" +
            "      \"similarOrientation\" : true,\n" +
            "      \"fullScaleBlockRadius\" : 100.0,\n" +
            "      \"fullScaleNonMaxSuppressionRadius\" : 20.0,\n" +
            "      \"gdStoredMatchWeight\" : 0.39\n" +
            "    },\n" +
            "    \"matchDerivationParameters\" : {\n" +
            "      \"matchRod\" : 0.92,\n" +
            "      \"matchModelType\" : \"RIGID\",\n" +
            "      \"matchRegularizerModelType\" : \"TRANSLATION\",\n" +
            "      \"matchInterpolatedModelLambda\" : 0.25,\n" +
            "      \"matchIterations\" : 10000,\n" +
            "      \"matchMaxEpsilon\" : 20.0,\n" +
            "      \"matchMinInlierRatio\" : 0.0,\n" +
            "      \"matchMinNumInliers\" : 10,\n" +
            "      \"matchMaxTrust\" : 3.0,\n" +
            "      \"matchFilter\" : \"SINGLE_SET\",\n" +
            "      \"matchFullScaleCoverageRadius\" : 300.0\n" +
            "    },\n" +
            "    \"minCombinedInliers\" : 0,\n" +
            "    \"minCombinedCoveragePercentage\" : 65.0\n" +
            "  }\n" +
            "}",

            // test 3 parameters
            "{\n" +
            "  \"matchClient\" : {\n" +
            "    \"baseDataUrl\" : \"http://10.40.3.162:8080/render-ws/v1\",\n" +
            "    \"owner\" : \"Z1217_19m\",\n" +
            "    \"collection\" : \"gd_test_3_Sec07_v1\"\n" +
            "  },\n" +
            "  \"featureRender\" : {\n" +
            "    \"renderScale\" : 0.6,\n" +
            "    \"renderWithFilter\" : true,\n" +
            "    \"renderWithoutMask\" : false\n" +
            "  },\n" +
            "  \"featureRenderClip\" : {\n" +
            "    \"clipWidth\" : 500,\n" +
            "    \"clipHeight\" : 500\n" +
            "  },\n" +
            "  \"featureExtraction\" : {\n" +
            "    \"fdSize\" : 4,\n" +
            "    \"minScale\" : 0.25,\n" +
            "    \"maxScale\" : 1.0,\n" +
            "    \"steps\" : 5\n" +
            "  },\n" +
            "  \"featureStorage\" : {\n" +
            "    \"requireStoredFeatures\" : false,\n" +
            "    \"maxFeatureCacheGb\" : 6,\n" +
            "    \"maxFeatureSourceCacheGb\" : 6\n" +
            "  },\n" +
            "  \"matchDerivation\" : {\n" +
            "    \"matchRod\" : 0.92,\n" +
            "    \"matchModelType\" : \"RIGID\",\n" +
            "    \"matchRegularizerModelType\" : \"TRANSLATION\",\n" +
            "    \"matchInterpolatedModelLambda\" : 0.25,\n" +
            "    \"matchIterations\" : 1000,\n" +
            "    \"matchMaxEpsilon\" : 20.0,\n" +
            "    \"matchMinInlierRatio\" : 0.0,\n" +
            "    \"matchMinNumInliers\" : 25,\n" +
            "    \"matchMaxTrust\" : 4.0,\n" +
            "    \"matchFilter\" : \"SINGLE_SET\",\n" +
            "    \"matchFullScaleCoverageRadius\" : 300.0,\n" +
            "    \"matchMinCoveragePercentage\" : 70.0\n" +
            "  },\n" +
            "  \"geometricDescriptorAndMatch\" : {\n" +
            "    \"gdEnabled\" : true,\n" +
            "    \"renderScale\" : 1.0,\n" +
            "    \"renderWithFilter\" : false,\n" +
            "    \"geometricDescriptorParameters\" : {\n" +
            "      \"numberOfNeighbors\" : 3,\n" +
            "      \"redundancy\" : 2,\n" +
            "      \"significance\" : 1.5,\n" +
            "      \"sigma\" : 6.04,\n" +
            "      \"threshold\" : 0.008,\n" +
            "      \"localization\" : \"THREE_D_QUADRATIC\",\n" +
            "      \"lookForMinima\" : true,\n" +
            "      \"lookForMaxima\" : false,\n" +
            "      \"similarOrientation\" : true,\n" +
            "      \"fullScaleBlockRadius\" : 100.0,\n" +
            "      \"fullScaleNonMaxSuppressionRadius\" : 20.0,\n" +
            "      \"gdStoredMatchWeight\" : 0.39\n" +
            "    },\n" +
            "    \"matchDerivationParameters\" : {\n" +
            "      \"matchRod\" : 0.92,\n" +
            "      \"matchModelType\" : \"RIGID\",\n" +
            "      \"matchRegularizerModelType\" : \"TRANSLATION\",\n" +
            "      \"matchInterpolatedModelLambda\" : 0.25,\n" +
            "      \"matchIterations\" : 10000,\n" +
            "      \"matchMaxEpsilon\" : 20.0,\n" +
            "      \"matchMinInlierRatio\" : 0.0,\n" +
            "      \"matchMinNumInliers\" : 10,\n" +
            "      \"matchMaxTrust\" : 3.0,\n" +
            "      \"matchFilter\" : \"SINGLE_SET\",\n" +
            "      \"matchFullScaleCoverageRadius\" : 300.0\n" +
            "    },\n" +
            "    \"runGeoRegardlessOfSiftResults\" : false,\n" +
            "    \"minCombinedInliers\" : 0,\n" +
            "    \"minCombinedCoveragePercentage\" : 60.0\n" +
            "  },\n" +
            "  \"pairJson\" : [\n" +
            "    \"/groups/flyem/data/alignment/flyem-alignment-ett/Z1217-19m/VNC/Sec07/alignment_scripts/montage/p3_pairs/tile_pairs_v1_acquire_dist_0_p230.json.gz\"\n" +
            "  ]\n" +
            "}"
    };

    private static final JsonUtils.Helper<SIFTPointMatchClient.Parameters> JSON_HELPER =
            new JsonUtils.Helper<>(SIFTPointMatchClient.Parameters.class);
}

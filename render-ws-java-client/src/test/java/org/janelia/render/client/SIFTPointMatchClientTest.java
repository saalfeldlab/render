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
import org.junit.jupiter.api.Test;

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

    private static final String TEST_PARAMETERS_0 = """
                    {
                      "matchClient" : {
                        "baseDataUrl" : "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                        "owner" : "Z1217_19m",
                        "collection" : "test_gd_matches_Sec07"
                      },
                      "featureRender" : {
                        "renderScale" : 0.15,
                        "renderWithFilter" : true,
                        "renderWithoutMask" : false
                      },
                      "featureRenderClip" : { },
                      "featureExtraction" : {
                        "fdSize" : 4,
                        "minScale" : 0.25,
                        "maxScale" : 1.0,
                        "steps" : 3
                      },
                      "featureStorage" : {
                        "requireStoredFeatures" : false,
                        "maxFeatureCacheGb" : 2,
                        "maxFeatureSourceCacheGb" : 2
                      },
                      "matchDerivation" : {
                        "matchRod" : 0.92,
                        "matchModelType" : "AFFINE",
                        "matchRegularizerModelType" : "RIGID",
                        "matchInterpolatedModelLambda" : 0.25,
                        "matchIterations" : 1000,
                        "matchMaxEpsilon" : 50.0,
                        "matchMinInlierRatio" : 0.0,
                        "matchMinNumInliers" : 10,
                        "matchMaxTrust" : 4.0,
                        "matchFilter" : "SINGLE_SET",
                        "matchFullScaleCoverageRadius" : 300.0
                      },
                      "geometricDescriptorAndMatch" : {
                        "gdEnabled" : true,
                        "renderScale" : 0.25,
                        "geometricDescriptorParameters" : {
                          "numberOfNeighbors" : 3,
                          "redundancy" : 1,
                          "significance" : 2.0,
                          "sigma" : 2.04,
                          "threshold" : 0.008,
                          "localization" : "NONE",
                          "lookForMinima" : true,
                          "lookForMaxima" : false,
                          "similarOrientation" : true,
                          "fullScaleBlockRadius" : 0.0,
                          "fullScaleNonMaxSuppressionRadius" : 120.0,
                          "gdStoredMatchWeight" : 0.4
                        },
                        "matchDerivationParameters" : {
                          "matchRod" : 0.92,
                          "matchModelType" : "RIGID",
                          "matchIterations" : 1000,
                          "matchMaxEpsilon" : 20.0,
                          "matchMinInlierRatio" : 0.0,
                          "matchMinNumInliers" : 4,
                          "matchMaxTrust" : 3.0,
                          "matchFilter" : "SINGLE_SET",
                          "matchFullScaleCoverageRadius" : 300.0
                        },
                        "minCombinedInliers" : 600,
                        "minCombinedCoveragePercentage" : 55.0
                      }
                    }""";

    // -----------------------------------------------------------------------------------------------
    private static final String TEST_PARAMETERS_1 = """
                    {
                        "featureAndMatchParameters" : {
                          "siftFeatureParameters" : {
                            "fdSize" : 4,
                            "minScale" : 0.25,
                            "maxScale" : 1.0,
                            "steps" : 5
                          },
                          "matchDerivationParameters" : {
                            "matchRod" : 0.92,
                            "matchModelType" : "RIGID",
                            "matchRegularizerModelType" : "TRANSLATION",
                            "matchInterpolatedModelLambda" : 0.25,
                            "matchIterations" : 1000,
                            "matchMaxEpsilon" : 60.0,
                            "matchMinInlierRatio" : 0.0,
                            "matchMinNumInliers" : 10,
                            "matchMaxTrust" : 4.0,
                            "matchFilter" : "SINGLE_SET",
                            "matchFullScaleCoverageRadius" : 300.0
                          },
                          "pClipPosition" : "LEFT",
                          "clipPixels" : 500
                        },
                        "pRenderParametersUrl" : "http://renderer-dev.int.janelia.org:8080/render-ws/v1/owner/Z1217_19m/project/Sec07/stack/v1_acquire/tile/19-02-18_163401_0-0-0.22718.0/render-parameters?filter=true&scale=0.6",
                        "qRenderParametersUrl" : "http://renderer-dev.int.janelia.org:8080/render-ws/v1/owner/Z1217_19m/project/Sec07/stack/v1_acquire/tile/19-02-18_163401_0-0-1.22718.0/render-parameters?filter=true&scale=0.6",
                        "geometricDescriptorAndMatchFilterParameters" : {
                          "gdEnabled" : true,
                          "renderScale" : 1.0,
                          "renderWithFilter" : false,
                          "geometricDescriptorParameters" : {
                            "numberOfNeighbors" : 3,
                            "redundancy" : 2,
                            "significance" : 1.5,
                            "sigma" : 6.04,
                            "threshold" : 0.008,
                            "localization" : "THREE_D_QUADRATIC",
                            "lookForMinima" : true,
                            "lookForMaxima" : false,
                            "similarOrientation" : true,
                            "fullScaleBlockRadius" : 100.0,
                            "fullScaleNonMaxSuppressionRadius" : 20.0,
                            "gdStoredMatchWeight" : 0.4
                          },
                          "matchDerivationParameters" : {
                            "matchRod" : 0.92,
                            "matchModelType" : "RIGID",
                            "matchRegularizerModelType" : "TRANSLATION",
                            "matchInterpolatedModelLambda" : 0.25,
                            "matchIterations" : 10000,
                            "matchMaxEpsilon" : 20.0,
                            "matchMinInlierRatio" : 0.0,
                            "matchMinNumInliers" : 10,
                            "matchMaxTrust" : 3.0,
                            "matchFilter" : "SINGLE_SET",
                            "matchFullScaleCoverageRadius" : 300.0
                          }
                        }
                      }""";

    // -----------------------------------------------------------------------------------------------
    private static final String TEST_PARAMETERS_2 = """
                    {
                      "matchClient" : {
                        "baseDataUrl" : "http://10.40.3.162:8080/render-ws/v1",
                        "owner" : "Z1217_19m",
                        "collection" : "gd_test_Sec07_v1"
                      },
                      "featureRender" : {
                        "renderScale" : 0.6,
                        "renderWithFilter" : true,
                        "renderWithoutMask" : false
                      },
                      "featureRenderClip" : {
                        "clipWidth" : 500,
                        "clipHeight" : 500
                      },
                      "featureExtraction" : {
                        "fdSize" : 4,
                        "minScale" : 0.25,
                        "maxScale" : 1.0,
                        "steps" : 5
                      },
                      "featureStorage" : {
                        "requireStoredFeatures" : false,
                        "maxFeatureCacheGb" : 6,
                        "maxFeatureSourceCacheGb" : 6
                      },
                      "matchDerivation" : {
                        "matchRod" : 0.92,
                        "matchModelType" : "RIGID",
                        "matchRegularizerModelType" : "TRANSLATION",
                        "matchInterpolatedModelLambda" : 0.25,
                        "matchIterations" : 1000,
                        "matchMaxEpsilon" : 60.0,
                        "matchMinInlierRatio" : 0.0,
                        "matchMinNumInliers" : 10,
                        "matchMaxTrust" : 4.0,
                        "matchFilter" : "SINGLE_SET",
                        "matchFullScaleCoverageRadius" : 300.0,
                        "matchMinCoveragePercentage" : 70.0
                      },
                      "geometricDescriptorAndMatch" : {
                        "gdEnabled" : true,
                        "renderScale" : 1.0,
                        "renderWithFilter" : false,
                        "geometricDescriptorParameters" : {
                          "numberOfNeighbors" : 3,
                          "redundancy" : 2,
                          "significance" : 1.5,
                          "sigma" : 6.04,
                          "threshold" : 0.008,
                          "localization" : "THREE_D_QUADRATIC",
                          "lookForMinima" : true,
                          "lookForMaxima" : false,
                          "similarOrientation" : true,
                          "fullScaleBlockRadius" : 100.0,
                          "fullScaleNonMaxSuppressionRadius" : 20.0,
                          "gdStoredMatchWeight" : 0.39
                        },
                        "matchDerivationParameters" : {
                          "matchRod" : 0.92,
                          "matchModelType" : "RIGID",
                          "matchRegularizerModelType" : "TRANSLATION",
                          "matchInterpolatedModelLambda" : 0.25,
                          "matchIterations" : 10000,
                          "matchMaxEpsilon" : 20.0,
                          "matchMinInlierRatio" : 0.0,
                          "matchMinNumInliers" : 10,
                          "matchMaxTrust" : 3.0,
                          "matchFilter" : "SINGLE_SET",
                          "matchFullScaleCoverageRadius" : 300.0
                        },
                        "minCombinedInliers" : 0,
                        "minCombinedCoveragePercentage" : 65.0
                      }
                    }""";

    // -----------------------------------------------------------------------------------------------
    private static final String TEST_PARAMETERS_3 = """
                    {
                      "matchClient" : {
                        "baseDataUrl" : "http://10.40.3.162:8080/render-ws/v1",
                        "owner" : "Z1217_19m",
                        "collection" : "gd_test_3_Sec07_v1"
                      },
                      "featureRender" : {
                        "renderScale" : 0.6,
                        "renderWithFilter" : true,
                        "renderWithoutMask" : false
                      },
                      "featureRenderClip" : {
                        "clipWidth" : 500,
                        "clipHeight" : 500
                      },
                      "featureExtraction" : {
                        "fdSize" : 4,
                        "minScale" : 0.25,
                        "maxScale" : 1.0,
                        "steps" : 5
                      },
                      "featureStorage" : {
                        "requireStoredFeatures" : false,
                        "maxFeatureCacheGb" : 6,
                        "maxFeatureSourceCacheGb" : 6
                      },
                      "matchDerivation" : {
                        "matchRod" : 0.92,
                        "matchModelType" : "RIGID",
                        "matchRegularizerModelType" : "TRANSLATION",
                        "matchInterpolatedModelLambda" : 0.25,
                        "matchIterations" : 1000,
                        "matchMaxEpsilon" : 20.0,
                        "matchMinInlierRatio" : 0.0,
                        "matchMinNumInliers" : 25,
                        "matchMaxTrust" : 4.0,
                        "matchFilter" : "SINGLE_SET",
                        "matchFullScaleCoverageRadius" : 300.0,
                        "matchMinCoveragePercentage" : 70.0
                      },
                      "geometricDescriptorAndMatch" : {
                        "gdEnabled" : true,
                        "renderScale" : 1.0,
                        "renderWithFilter" : false,
                        "geometricDescriptorParameters" : {
                          "numberOfNeighbors" : 3,
                          "redundancy" : 2,
                          "significance" : 1.5,
                          "sigma" : 6.04,
                          "threshold" : 0.008,
                          "localization" : "THREE_D_QUADRATIC",
                          "lookForMinima" : true,
                          "lookForMaxima" : false,
                          "similarOrientation" : true,
                          "fullScaleBlockRadius" : 100.0,
                          "fullScaleNonMaxSuppressionRadius" : 20.0,
                          "gdStoredMatchWeight" : 0.39
                        },
                        "matchDerivationParameters" : {
                          "matchRod" : 0.92,
                          "matchModelType" : "RIGID",
                          "matchRegularizerModelType" : "TRANSLATION",
                          "matchInterpolatedModelLambda" : 0.25,
                          "matchIterations" : 10000,
                          "matchMaxEpsilon" : 20.0,
                          "matchMinInlierRatio" : 0.0,
                          "matchMinNumInliers" : 10,
                          "matchMaxTrust" : 3.0,
                          "matchFilter" : "SINGLE_SET",
                          "matchFullScaleCoverageRadius" : 300.0
                        },
                        "runGeoRegardlessOfSiftResults" : false,
                        "minCombinedInliers" : 0,
                        "minCombinedCoveragePercentage" : 60.0
                      },
                      "pairJson" : [
                        "/groups/flyem/data/alignment/flyem-alignment-ett/Z1217-19m/VNC/Sec07/alignment_scripts/montage/p3_pairs/tile_pairs_v1_acquire_dist_0_p230.json.gz"
                      ]
                    }""";

    private static final String[] TEST_PARAMETERS_JSON = {
            TEST_PARAMETERS_0, TEST_PARAMETERS_1, TEST_PARAMETERS_2, TEST_PARAMETERS_3
    };

    private static final JsonUtils.Helper<SIFTPointMatchClient.Parameters> JSON_HELPER =
            new JsonUtils.Helper<>(SIFTPointMatchClient.Parameters.class);
}

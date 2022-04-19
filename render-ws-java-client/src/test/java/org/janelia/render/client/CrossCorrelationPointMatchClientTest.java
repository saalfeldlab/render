package org.janelia.render.client;

import java.util.Collections;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.MontageRelativePosition;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link CrossCorrelationPointMatchClient} class.
 *
 * @author Eric Trautman
 */
public class CrossCorrelationPointMatchClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new CrossCorrelationPointMatchClient.Parameters());
    }

    // --------------------------------------------------------------
    // The following methods support ad-hoc interactive testing with external render web services.
    // Consequently, they aren't included in the unit test suite.

    public static void main(final String[] args) {
        try {

            final int defaultTestIndex = 0;
            final int testIndex = args.length == 0 ? defaultTestIndex : Integer.parseInt(args[0]);

            runTestWithExternalDependencies(testIndex);
            
        } catch (final Throwable t) {
            t.printStackTrace();
        }
    }

    private static void runTestWithExternalDependencies(final int testIndex)
            throws Exception {

        final String owner = "Z0620_23m_VNC";
        final String project = "Sec25";
        final String stack = "v2_acquire";

        final String urlTemplateString = "{baseDataUrl}/owner/" + owner + "/project/" + project + "/stack/" + stack +
                                         "/tile/{id}/render-parameters";
        final CanvasId pCanvasId = getCanvasId(testIndex, true);
        final CanvasId qCanvasId = getCanvasId(testIndex, false);
        final OrderedCanvasIdPair pair = new OrderedCanvasIdPair(pCanvasId, qCanvasId, null);
        final List<OrderedCanvasIdPair> pairList = Collections.singletonList(pair);
        final RenderableCanvasIdPairs renderableCanvasIdPairs = new RenderableCanvasIdPairs(urlTemplateString,
                                                                                            pairList);

        final CrossCorrelationPointMatchClient.Parameters parameters = getParameters(testIndex);

        // HACK: set up match client with invalid collection name so that nothing actually gets saved
        parameters.matchClient.baseDataUrl = "http://renderer-dev.int.janelia.org:8080/render-ws/v1";
        parameters.matchClient.owner = owner;
        parameters.matchClient.collection = "##_invalid_collection_name_##";

        final CrossCorrelationPointMatchClient client = new CrossCorrelationPointMatchClient(parameters);

        client.generateMatchesForPairs(renderableCanvasIdPairs,
                                       parameters.matchClient.baseDataUrl,
                                       parameters.featureRender,
                                       parameters.featureRenderClip,
                                       parameters.correlation,
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

    private static CrossCorrelationPointMatchClient.Parameters getParameters(final int testIndex) {
        final String json = TEST_PARAMETERS_JSON[testIndex];
        return JSON_HELPER.fromJson(json);
    }

    private static final String[][] TEST_PAIR_IDS = {
            // test 0 canvas ID info: http://renderer.int.janelia.org:8080/render-ws/view/tile-with-neighbors.html?tileId=20-07-12_061129_0-0-1.400.0&renderScale=0.0639968396622389&renderStackOwner=Z0620_23m_VNC&renderStackProject=Sec25&renderStack=v2_acquire&matchOwner=Z0620_23m_VNC&matchCollection=Sec25_v2
            { "400.0", "20-07-12_061129_0-0-0.400.0", "LEFT", "400.0", "20-07-12_061129_0-0-1.400.0", "RIGHT" }
    };

    private static final String[] TEST_PARAMETERS_JSON = {

            // -----------------------------------------------------------------------------------------------
            // test 0 parameters:
            "{\n" +
            "  \"matchClient\" : {\n" +
            "    \"baseDataUrl\" : \"http://renderer-dev.int.janelia.org:8080/render-ws/v1\",\n" +
            "    \"owner\" : \"Z0620_23m_VNC\",\n" +
            "    \"collection\" : \"test_matches_Sec25\"\n" +
            "  },\n" +
            "  \"featureRender\" : { " +
            "    \"renderScale\" : 1.0, \"renderWithFilter\" : false, \"renderWithoutMask\" : false\n" +
            "  },\n" +
            "  \"featureRenderClip\" : { \"clipWidth\": 250 },\n" +
            "  \"correlation\" : {\n" +
            "    \"fullScaleSampleSize\" : 250,\n" +
            "    \"fullScaleStepSize\" : 5,\n" +
            "    \"minResultThreshold\" : 0.5,\n" +
            "    \"checkPeaks\" : 50\n" +
            "  },\n" +
            "  \"matchDerivation\" : {\n" +
            "    \"matchRod\" : 0.92,\n" +
            "    \"matchModelType\" : \"TRANSLATION\",\n" +
            "    \"matchIterations\" : 1000,\n" +
            "    \"matchMaxEpsilon\" : 2.0,\n" +
            "    \"matchMinInlierRatio\" : 0.0,\n" +
            "    \"matchMinNumInliers\" : 20,\n" +
            "    \"matchMaxTrust\" : 3.0,\n" +
            "    \"matchFilter\" : \"SINGLE_SET\"\n" +
            "  }\n" +
            "}"
    };

    private static final JsonUtils.Helper<CrossCorrelationPointMatchClient.Parameters> JSON_HELPER =
            new JsonUtils.Helper<>(CrossCorrelationPointMatchClient.Parameters.class);
}

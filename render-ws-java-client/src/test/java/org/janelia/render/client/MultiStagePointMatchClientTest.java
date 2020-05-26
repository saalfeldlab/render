package org.janelia.render.client;

import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.janelia.alignment.match.parameters.MatchStageParameters;
import org.janelia.render.client.MultiStagePointMatchClient.StageResources;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link MultiStagePointMatchClient} class.
 *
 * @author Eric Trautman
 */
public class MultiStagePointMatchClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new MultiStagePointMatchClient.Parameters());
    }

    @Test
    public void testBuildStageResourcesList() throws Exception {
        final String baseDataUrl = "http://rendertest.janelia.org";
        final MultiStagePointMatchClient.Parameters parameters = new MultiStagePointMatchClient.Parameters();
        parameters.matchClient.baseDataUrl = baseDataUrl;
        final MultiStagePointMatchClient client = new MultiStagePointMatchClient(parameters);
        final String urlTemplateString =
                "{baseDataUrl}/owner/flyTEM/project/FAFB00/stack/v12_acquire_merged/tile/{id}/render-parameters";
        final RenderableCanvasIdPairs renderableCanvasIdPairs = new RenderableCanvasIdPairs(urlTemplateString,
                                                                                            new ArrayList<>());
        final List<MatchStageParameters> stageParametersList =
                MatchStageParameters.fromJsonArrayFile("src/test/resources/match_stage_montage.json");
        final List<StageResources> stageResourcesList =
                client.buildStageResourcesList(renderableCanvasIdPairs, baseDataUrl, stageParametersList);

        Assert.assertEquals("invalid size", 5, stageResourcesList.size());

        final StageResources stage3 = stageResourcesList.get(3);
        Assert.assertTrue("stage 3 feature template should match stage 2",
                          stage3.siftUrlTemplateMatchesPriorStageTemplate);

        final StageResources stage4 = stageResourcesList.get(4);
        Assert.assertFalse("stage 4 feature template should NOT match stage 3",
                           stage4.siftUrlTemplateMatchesPriorStageTemplate);
    }

    // --------------------------------------------------------------
    // The following methods support ad-hoc interactive testing with external render web services.
    // Consequently, they aren't included in the unit test suite.

    public static void main(final String[] args) {

        final String[] effectiveArgs = new String[] {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "trautmane",
                "--collection", "test_multi_stage",
                "--stageJson", " /Users/trautmane/Desktop/test_multi_stage/match_stage_montage.json",
                "--pairJson", " /Users/trautmane/Desktop/test_multi_stage/pairs.json"
        };

        MultiStagePointMatchClient.main(effectiveArgs);
    }


}

package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
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

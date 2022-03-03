package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link HackMergeTransformClient} class.
 *
 * @author Eric Trautman
 */
public class HackMergeTransformClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new HackMergeTransformClient.Parameters());
    }

    // --------------------------------------------------------------
    // The following method supports ad-hoc interactive testing with external render web services.
    // Consequently, they aren't included in the unit test suite.

    public static void main(final String[] args) {
        final String[] testArgs = {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "flyem",
                "--project", "Z0419_25_Alpha3",
                "--firstAlignedStack", "v1_acquire_sp_nodyn_t4636",
                "--firstStackLastZ", "9505",
                "--secondAlignedStack", "v3_acquire_sp1_adaptive",
                "--secondStackFirstZ", "9506",
        };

        HackMergeTransformClient.main(testArgs);
    }

}

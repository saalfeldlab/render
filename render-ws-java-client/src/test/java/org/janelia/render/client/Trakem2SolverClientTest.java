package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link Trakem2SolverClient} class.
 *
 * @author Eric Trautman
 */
public class Trakem2SolverClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new Trakem2SolverClient.Parameters());
    }

    // --------------------------------------------------------------
    // The following method supports ad-hoc interactive testing with external render web services.
    // Consequently, they aren't included in the unit test suite.

    public static void main(final String[] args) {
        final String[] testArgs = {
                "--baseDataUrl", "http://renderer-dev:8080/render-ws/v1",
                "--owner", "Z0620_23m_VNC",
                "--project", "Sec25",
                "--stack", "v2_acquire_trimmed",
                "--targetStack", "test_cc",

//                "--minZ", "2610",
//                "--maxZ", "2610",
//                "--matchCollection", "Sec25_v2_b",

                "--minZ", "10772",
                "--maxZ", "10772",
                "--matchCollection", "Sec25_v2_test_cc",

                "--completeTargetStack",
                "--regularizerModelType", "RIGID",
//                "--optimizerLambdas", "0.1"
        };

        Trakem2SolverClient.main(testArgs);
    }

}

package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link Trakem2SolverPreAlignClient} class.
 *
 * @author Eric Trautman
 */
public class Trakem2SolverPreAlignClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new Trakem2SolverPreAlignClient.Parameters());
    }

    // --------------------------------------------------------------
    // The following method supports ad-hoc interactive testing with external render web services.
    // Consequently, they aren't included in the unit test suite.

    public static void main(final String[] args) {
        final String[] testArgs = {
                "--baseDataUrl", "http://renderer-dev:8080/render-ws/v1",
                "--owner", "Z1217_19m",
                "--project", "Sec07",

                "--stack", "v1_acquire",
                "--targetStack", "just_a_test_stack", //""v1_acquire_pre_align_trans",
                "--maxZ", "300",

//                            "--stack", "slim_24800_25800",
//                            "--targetStack", "slim_24800_25800_pre_align_trans",
//                            "--maxLayersPerBatch", "100",

                "--completeTargetStack",
                "--matchCollection", "gd_test_3_Sec07_v1"
        };

        Trakem2SolverPreAlignClient.main(testArgs);
    }

}

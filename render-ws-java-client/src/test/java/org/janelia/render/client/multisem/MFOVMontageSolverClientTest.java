package org.janelia.render.client.multisem;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link MFOVMontageSolverClient} class.
 *
 * @author Eric Trautman
 */
public class MFOVMontageSolverClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new MFOVMontageSolverClient.Parameters());
    }

    // --------------------------------------------------------------
    // The following method supports ad-hoc interactive testing with external render web services.
    // Consequently, they aren't included in the unit test suite.

    public static void main(final String[] args) {
        final String[] testArgs = {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "hess",
                "--project", "wafer_52c",

                "--stack", "v1_acquire_slab_001",
                "--z", "1247", "1248", "1249", "1250",
                "--mfov", "001_000001", "001_000002", "001_000003", "001_000004",
                "001_000005", "001_000006", "001_000007",

//                "--z", "1250",
//                "--mfov", "001_000003",

                "--targetStack", "v1_acquire_slab_001_montage_20221209_1712",
//                "--optimizerLambdas", "1.0,0.5,0.1,0.01",
                "--optimizerLambdas", "0.01",
                "--maxIterations", "1000",

                "--completeTargetStack",
                "--matchCollection", "wafer_52c_v1_patched"
        };

        MFOVMontageSolverClient.main(testArgs);
    }

}

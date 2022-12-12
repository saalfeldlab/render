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
                "--unconnectedMFOVPairsFile", "/Users/trautmane/Desktop/mfov/unconnected_mfov_pairs.json",
                "--montageStackSuffix", "_montage_20221212_1145",
                "--completeMontageStacks",
        };

        MFOVMontageSolverClient.main(testArgs);
    }

}

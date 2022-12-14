package org.janelia.render.client.multisem;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link UnconnectedCrossMFOVClient} class.
 *
 * @author Eric Trautman
 */
public class UnconnectedCrossMFOVClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new UnconnectedCrossMFOVClient.Parameters());
    }

    public static void main(final String[] args) {

        final String[] effectiveArgs = (args != null) && (args.length > 0) ? args : new String[] {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "hess",
                "--project", "wafer_52c",
                "--stack", "v1_acquire_slab_001",
                "--matchCollection", "wafer_52_cut_00030_to_00039_v1",
                "--minPairsForConnection", "6",
                "--unconnectedMFOVPairsDirectory", "/Users/trautmane/Desktop/mfov",
                "--montageStackSuffix", "_mfov_montage_20221214_1217"
        };

        UnconnectedCrossMFOVClient.main(effectiveArgs);
    }
}

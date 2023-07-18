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
                "--owner", "hess_wafer_53",
                "--project", "cut_000_to_009",
//                "--stack", "c001_s145_v01",
                "--allStacksInProject",
                "--minPairsForConnection", "6",
                "--unconnectedMFOVPairsDirectory", "/Users/trautmane/Desktop/mfov",
                "--montageStackSuffix", "_mfov_montage"
        };

        UnconnectedCrossMFOVClient.main(effectiveArgs);
    }
}

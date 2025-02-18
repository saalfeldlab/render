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
                "--baseDataUrl", "http://10.40.3.113:8080/render-ws/v1",
                "--owner", "trautmane",
                "--project", "w60_serial_290_to_299",
                "--stack", "w60_s296_r00_d00_google_cluster0",
                "--minPairsForConnection", "1",
                "--unconnectedMFOVPairsDirectory", "/Users/trautmane/Desktop/unconnectedMFOVPairs",
                "--montageStackSuffix", "_mfov_montage"
        };

        UnconnectedCrossMFOVClient.main(effectiveArgs);
    }
}

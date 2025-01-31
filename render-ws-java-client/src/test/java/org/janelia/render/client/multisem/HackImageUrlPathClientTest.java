package org.janelia.render.client.multisem;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link HackImageUrlPathClient} class.
 */
public class HackImageUrlPathClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new PreAlignClient.Parameters());
    }

    public static void main(final String[] args) {
        final String[] effectiveArgs = {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "trautmane",
                "--project", "w60_serial_290_to_299",
                "--stack", "w60_s296_r00_d00",
                "--targetStack", "w60_s296_r00_d00_google",
                "--transformationType", "GOOGLE_TEST_CLOUD"
        };

        HackImageUrlPathClient.main(effectiveArgs);
    }
}

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
                "--owner", "hess_wafers_60_61",
                "--project", "w60_serial_360_to_369",
                "--stack", "w60_s360_r00_d30",
                "--targetStack", "w60_s360_r00_d30_gc",
                "--transformationType", "GOOGLE_CLOUD_WAFER_60"
        };

        HackImageUrlPathClient.main(effectiveArgs);
    }
}

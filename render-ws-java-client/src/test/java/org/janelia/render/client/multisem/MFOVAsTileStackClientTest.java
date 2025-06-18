package org.janelia.render.client.multisem;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link MFOVAsTileStackClient} class.
 */
public class MFOVAsTileStackClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new MFOVAsTileStackClient.Parameters());
    }

    public static void main(final String[] args) {

        final String[] effectiveArgs = (args != null) && (args.length > 0) ? args : new String[] {
                "--baseDataUrl", "http://em-services-1.int.janelia.org:8080/render-ws/v1",
                "--owner", "hess_wafers_60_61",
                "--project", "w60_serial_360_to_369",
                "--stack", "w60_s360_r00_gc",
                "--mfovTileRenderScale", "0.2",
                "--mfovTileStackSuffix", "_mt5"
        };

        MFOVAsTileStackClient.main(effectiveArgs);
    }
}

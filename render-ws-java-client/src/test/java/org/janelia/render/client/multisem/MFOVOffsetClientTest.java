package org.janelia.render.client.multisem;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link MFOVOffsetClient} class.
 */
public class MFOVOffsetClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new UnconnectedMontageMFOVClient.Parameters());
    }

    public static void main(final String[] args) {

        final String[] effectiveArgs = (args != null) && (args.length > 0) ? args : new String[] {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "hess_wafers_60_61",
                "--project", "w60_serial_360_to_369",
                "--stack", "w60_s360_r00_d20_gc_timeout_mfov_31_to_33_z_19_to_22",
                "--matchCollection", "w60_s360_r00_d20_gc_match",
                "--mopMaxNeighborPixelDistance", "1000",
                "--mopMaxNeighborCount", "10",
                "--mopMinNumberOfMatchInliers", "12",
                "--mopRenderScale", "0.4",
                "--mopMaxAbsoluteMFOVTranslationDelta", "5000",
                "--mopOffsetStackSuffix", "_osb"
        };

        // note: owner + "__" + project + "__" + stack + "__transform" <= 111 character max
        // hess_wafers_60_61__w60_serial_360_to_369__w60_s360_r00_d20_gc_timeout_mfov_31_to_33_z_19_to_22_osb__transform
        MFOVOffsetClient.main(effectiveArgs);
    }
}

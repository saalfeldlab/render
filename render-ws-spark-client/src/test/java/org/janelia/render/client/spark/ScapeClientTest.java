package org.janelia.render.client.spark;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link ScapeClient} class.
 *
 * @author Eric Trautman
 */
public class ScapeClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new ScapeClient.Parameters());
    }

    // --------------------------------------------------------------
    // The following methods support ad-hoc interactive testing with external render web services.
    // Consequently, they aren't included in the unit test suite.

    public static void main(final String[] args) throws Exception {

        final String[] effectiveArgs = {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "hess_wafers_60_61",
                "--project", "w60_serial_360_to_369",
                "--stack", "w60_s360_r00_d20_gc_mfov_32_rxy",
                "--resolutionUnit", "nm",
                "--rootDirectory", "/nrs/hess/render/scapes",
//                "--scale", "0.01",
                "--scaledScapeSize", "4096",
                "--format", "jpg",
                "--useLayerBounds", "true",
                "--minZ", "26",
                "--maxZ", "26",
//                "--hackStackSuffix", "d"
        };

        final ScapeClient.Parameters parameters = new ScapeClient.Parameters();
        parameters.parse(effectiveArgs);

        final ScapeClient scapeClient = new ScapeClient();
        scapeClient.createContextAndRun(parameters, 1);
    }
}

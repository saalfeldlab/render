package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link RenderSectionClient} class.
 *
 * @author Eric Trautman
 */
public class RenderSectionClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new RenderSectionClient.Parameters());
    }

    // --------------------------------------------------------------
    // The following methods support ad-hoc interactive testing with external render web services.
    // Consequently, they aren't included in the unit test suite.

    public static void main(final String[] args) {
        try {
            final String[] testArgs = {
                    "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                    "--owner", "Z1217_19m",
                    "--project", "Sec06",
                    "--stack", "v2_patch_msolve_fine",
                    "--rootDirectory", "/Users/trautmane/Desktop/scapes",
                    "--scale", "1.0",
                    "--maxIntensity", "65535",
                    "--doFilter", "false",
                    "--fillWithNoise", "false",
                    "--useStackBounds",
                    "--renderTileLabels",
                    "2309", "2310"
            };

            RenderSectionClient.main(testArgs);

        } catch (final Throwable t) {
            t.printStackTrace();
        }
    }

}

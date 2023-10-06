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
                    "--owner", "cellmap",
                    "--project", "jrc_mus_thymus_1",
                    "--stack", "v1_acquire",
//                    "--resolutionUnit", "nm",
                    "--rootDirectory", "/Users/trautmane/Desktop/scape_test",
                    "--scale", "0.1",
                    "--format", "tif",
                    "--convertToGray",
                    "1000"
                    };

            RenderSectionClient.main(testArgs);

        } catch (final Throwable t) {
            throw new RuntimeException("caught exception", t);
        }
    }

}

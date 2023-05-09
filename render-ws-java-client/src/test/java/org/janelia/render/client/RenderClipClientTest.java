package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link RenderClipClient} class.
 *
 * @author Eric Trautman
 */
public class RenderClipClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new RenderClipClient.Parameters());
    }

    // --------------------------------------------------------------
    // The following methods support ad-hoc interactive testing with external render web services.
    // Consequently, they aren't included in the unit test suite.

    public static void main(final String[] args) {
        try {
            final String[] testArgs = {
                    "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                    "--owner", "fibsem",
                    "--project", "Z0422_17_VNC_1",
                    "--stack", "v6_acquire_trimmed_align",
                    "--rootDirectory", "/nrs/fibsem/render/clip",
                    "--singleOutputDirectory",
                    "--scale", "1.0",
                    "--format", "tif",
                    "--hackStack", "v6_acquire_trimmed_align_clip",
                    "--completeHackStack",
                    "--minX", "-3600", "--maxX", "-2900", "--minY", "2150", "--maxY", "2850",
                    "--z", "64588",
            };

            RenderClipClient.main(testArgs);
            
        } catch (final Throwable t) {
            t.printStackTrace();
        }
    }


}

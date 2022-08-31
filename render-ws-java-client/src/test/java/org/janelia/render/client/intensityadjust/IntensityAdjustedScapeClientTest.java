package org.janelia.render.client.intensityadjust;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.IntensityAdjustParameters;
import org.junit.Test;

/**
 * Tests the {@link IntensityAdjustedScapeClient} class.
 *
 * @author Eric Trautman
 */
public class IntensityAdjustedScapeClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new IntensityAdjustParameters());
    }

    public static void main(final String[] args) {

        final String[] effectiveArgs = (args != null) && (args.length > 0) ? args : new String[] {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "Z0720_07m_VNC",

                // one two-tile layer of Sec06 or Sec07 takes roughly 60 seconds to run on laptop
//                "--project", "Sec06",
//                "--stack", "v5_acquire_trimmed_align",

                "--project", "Sec07",
                "--stack", "v4_acquire_trimmed_align",

                "--format", "png",
                "--rootDirectory", "/nrs/flyem/render/ic_test",
                "--correctionMethod", "GLOBAL_PER_SLICE",
                "--z", "1812",
        };

        IntensityAdjustedScapeClient.main(effectiveArgs);
    }

}

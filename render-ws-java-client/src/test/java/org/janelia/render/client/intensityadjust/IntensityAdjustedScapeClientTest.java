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

                // Sec06 has one transform per tile
//                "--project", "Sec06",
//                "--stack", "v5_acquire_trimmed_align",

                // Sec07 has two transforms per tile
//                "--project", "Sec07",
//                "--stack", "v4_acquire_trimmed_align",

                // Sec19 has three transforms for tiles in column 0 and two transforms for all other tiles
                // z 7547 of Sec19 has obvious seam between column 0 and 1 tiles with original ic code
                "--project", "Sec19",
                "--stack", "v7_acquire_trimmed_align_straightened",

                "--format", "png",
                "--rootDirectory", "/nrs/flyem/render/ic_test",
                "--correctionMethod", "GLOBAL_PER_SLICE",
                "--z", "7547",
        };

        IntensityAdjustedScapeClient.main(effectiveArgs);
    }

}

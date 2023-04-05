package org.janelia.render.client.intensityadjust;

import java.text.SimpleDateFormat;
import java.util.Date;

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

        final String alignedStackName = "slab_045_all_align_t2_mfov_4_center_19";
        final String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        final String intensityCorrectedStackName = alignedStackName + "_ic_" + timestamp;

        final String[] effectiveArgs = (args != null) && (args.length > 0) ? args : new String[] {
                "--baseDataUrl", "http://em-services-1.int.janelia.org:8080/render-ws/v1",
                "--owner", "hess",
                "--project", "wafer_52_cut_00030_to_00039",
                "--stack", alignedStackName,
                "--intensityCorrectedFilterStack", intensityCorrectedStackName,
                "--completeCorrectedStack",
                "--correctionMethod", "GLOBAL_PER_SLICE",
                "--zDistance", "3",
                "--minZ", "1260", // 1260
                "--maxZ", "1263", // 1285
        };

        IntensityAdjustedScapeClient.main(effectiveArgs);
    }

}

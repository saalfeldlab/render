package org.janelia.render.client.intensityadjust;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.IntensityAdjustParameters;
import org.janelia.render.client.parameter.IntensityAdjustParameters.StrategyName;
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
//        final StrategyName strategyName = StrategyName.AFFINE;
//        final StrategyName strategyName = StrategyName.FIRST_LAYER_QUADRATIC;
        final StrategyName strategyName = StrategyName.ALL_LAYERS_QUADRATIC;
        final String intensityCorrectedStackName = alignedStackName + "_ic_" + strategyName;

        final String[] effectiveArgs = (args != null) && (args.length > 0) ? args : new String[] {
                "--baseDataUrl", "http://em-services-1.int.janelia.org:8080/render-ws/v1",
                "--owner", "hess",
                "--project", "wafer_52_cut_00030_to_00039",
                "--stack", alignedStackName,
                "--intensityCorrectedFilterStack", intensityCorrectedStackName,
                "--completeCorrectedStack",
                "--correctionMethod", "GLOBAL_PER_SLICE",
                "--strategy", String.valueOf(strategyName),
                "--numThreads", "12",

                // for entire stack minZ is 1260 and maxZ is 1285

                // "--zDistance", "1", "--minZ", "1260", "--maxZ", "1261" // processing time:  1 minute
                // "--zDistance", "2", "--minZ", "1260", "--maxZ", "1262" // processing time:  2 minutes  6 seconds
                // "--zDistance", "3", "--minZ", "1260", "--maxZ", "1263" // processing time:  4 minutes 14 seconds
                // "--zDistance", "4", "--minZ", "1260", "--maxZ", "1264" // processing time:  6 minutes 54 seconds
                // "--zDistance", "5", "--minZ", "1260", "--maxZ", "1265" // processing time: 10 minutes 17 seconds
                "--zDistance", "6", "--minZ", "1260", "--maxZ", "1266" // processing time: 14 minutes 30 seconds
        };

        IntensityAdjustedScapeClient.main(effectiveArgs);
    }

}

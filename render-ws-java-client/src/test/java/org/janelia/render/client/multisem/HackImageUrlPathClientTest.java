package org.janelia.render.client.multisem;

import java.util.function.UnaryOperator;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link HackImageUrlPathClient} class.
 */
public class HackImageUrlPathClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new PreAlignClient.Parameters());
    }

    public static void main(final String[] args) {

        final String[] effectiveArgs = {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "hess_wafers_60_61",
                "--project", "test_creep_try2",
                "--stack", "w61_s109_r00_gc_par",
                "--targetStack", "w61_s109_r00_gc_par_corrected",
                "--transformationType", "HAYWORTH_CREEP_CORRECTION"
        };
//        final String[] effectiveArgs = {
//                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
//                "--owner", "hess_wafers_60_61",
//                "--project", "w60_serial_360_to_369",
//                "--stack", "w60_s360_r00_d20_gc",
//                "--targetStack", "w60_s360_r00_d20_gc_timeout",
//                "--transformationType", "NO_PATH_TRANSFORMATION"
//        };
//        final String[] effectiveArgs = {
//                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
//                "--owner", "fibsem",
//                "--project", "jrc_P3_E5_D1_N2",
//                "--stack", "v1_acquire_trimmed_align_v2",
//                "--targetStack", "v1_acquire_trimmed_align_v2_channel_1",
//                "--transformationType", "FIBSEM_CHANNEL_ONE"
//        };
        HackImageUrlPathClient.main(effectiveArgs);

//        for (int serialNumber = 360; serialNumber < 370; serialNumber++) {
//            final String stack = String.format("w60_s%d_r00_d30", serialNumber);
//            createGoogleCloudStackWithTimeoutLoader("hess_wafers_60_61",
//                                                    "w60_serial_360_to_369",
//                                                    stack,
//                                                    "_gc_timeout");
//        }

    }

    @SuppressWarnings({"SameParameterValue", "unused"})
    private static void createGoogleCloudStackWithTimeoutLoader(final String owner,
                                                                final String project,
                                                                final String stack,
                                                                final String targetStackSuffix) {

        final String[] effectiveArgs = {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", owner,
                "--project", project,
                "--stack", stack,
                "--targetStack", stack + targetStackSuffix,
                "--transformationType", "GOOGLE_CLOUD_WAFER_60"
        };

        final HackImageUrlPathClient.Parameters parameters = new HackImageUrlPathClient.Parameters();
        parameters.parse(effectiveArgs);

        final UnaryOperator<String> pathTransformation =
                HackImageUrlPathClient.PathTransformationType.GOOGLE_CLOUD_WAFER_60.getOperator();
        final HackImageUrlPathClient client = new HackImageUrlPathClient(parameters, pathTransformation);

        try {
            client.fixStackData();
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }

    }

}

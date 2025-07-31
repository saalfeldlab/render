package org.janelia.render.client.tile;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link RenderTilesClient} class.
 *
 * @author Eric Trautman
 */
public class RenderTilesClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new RenderTilesClient.Parameters());
    }

    // --------------------------------------------------------------
    // The following methods support ad-hoc interactive testing with external render web services.
    // Consequently, they aren't included in the unit test suite.

    public static void main(final String[] args) {
        try {
            final String[] testArgs = {
                    "--baseDataUrl", "http://em-services-1.int.janelia.org:8080/render-ws/v1",
                    "--owner", "hess_wafers_60_61",
                    "--project", "w60_serial_360_to_369",
                    "--stack", "w60_s360_r00_gc_z_1_and_2_mat",
                    "--rootDirectory", "/nrs/hess/data/hess_wafers_60_61/tiles_mfov",
//                    "--rootDirectory", "gs://storage.googleapis.com/janelia-spark-test/test_upload_ett/mfov_as_tile",
//                    "--runTimestamp", "20241123_160000",
                    "--scale", "1.0",
                    "--format", "png",
                    "--excludeMask",
                    "--excludeAllTransforms",
//                    "--filterListName", "jrc_mpi_psc120_1a1-destreak-16bit",
                    "--hackStack", "test_hack_m0026",
                    "--renderType", "EIGHT_BIT",
                    "--completeHackStack",
                    "--renderTileImagesLocally",
                    "--z", "1",
                    "--tileIdPattern", ".*_m005."
            };

            RenderTilesClient.main(testArgs);
            
        } catch (final Throwable t) {
            //noinspection CallToPrintStackTrace
            t.printStackTrace();
        }
    }


}

package org.janelia.render.client.multisem;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link MFOVMatchClient} class.
 *
 * @author Eric Trautman
 */
public class MFOVMatchClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new MFOVMatchClient.Parameters());
    }

    public static void main(final String[] args) {

        final String[] effectiveArgs = (args != null) && (args.length > 0) ? args : new String[] {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "hess",
                "--project", "wafer_52c",
                "--stack", "v1_acquire_slab_001",
                "--xyNeighborFactor", "0.6", // for tiles completely within mFov
                "--stored_match_weight", "0.0001",

                "--matchCollection", "wafer_52c_v1",

                "--mfov", "001_000004",
                "--matchStorageFile", "/tmp/001_000004_missing.json",

                // from Stephan's DebugTransformed... run:
                //"--tileIdPattern", "001_000004_005.*", "--tileId2", "001_000004_006",
                // [1.0, 0.0, 946.3353229867118], [-0.0, 1.0, 1650.4545454545455]
                //maxE=1.9563283848992057 [3,3](AffineTransform[[1.000004430187868, -7.6385492251E-5, 947.0522512333544], [-2.214604778E-5, 1.000051525513153, 1650.523026926238]]) 0.6812331662453539, AffineModel2D
                //maxE=2.3338589474988547 [3,3](AffineTransform[[1.0, -0.0, 946.3353229867118], [0.0, 1.0, 1650.4545454545455]]) 0.9487593150079552, AffineModel2D

                // same run with MFOVMatchClient has different results, need to determine why ...
                "--pTileId", "001_000004_005_20220408_060427.1250.0",
                "--qTileId", "001_000004_006_20220408_060427.1250.0",
//                "--minZ", "1234",
//                "--maxZ", "1235",
                "--z", "1234", "1249", //"1250"
                // existingMatchModel after fit is [3,3](AffineTransform[[1.000174236446295, 0.004337017990846, -956.3291600882663], [-0.001175823861387, 1.000052427782231, -1648.7027966452395]]) 1.7976931348623157E308
                // existingCornerMatchModel after fit is [3,3](AffineTransform[[1.00005263248074, -0.00270921004834, -944.3137273198181], [-0.001259486206156, 0.998437347216311, -1645.965212703586]]) 1.7976931348623157E308
                // existingCornerMatchModel error is 4.720476749908454 and maxError is 23.305402102991575
        };

        MFOVMatchClient.main(effectiveArgs);

    }
}

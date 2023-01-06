package org.janelia.render.client.tile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.transform.SEMDistortionTransformA;
import org.janelia.alignment.util.FileUtil;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.junit.Test;

/**
 * Tests the {@link RenderTileWithTransformsClient} class.
 *
 * @author Eric Trautman
 */
public class RenderTileWithTransformsClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new RenderTileWithTransformsClient.Parameters());
    }

    // --------------------------------------------------------------
    // The following methods support ad-hoc interactive testing with external render web services.
    // Consequently, they aren't included in the unit test suite.

    public static void main(final String[] args) {
        try {
//            final String[] testArgs = {
//                    "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
//                    "--owner", "reiser",
//                    "--project", "Z0422_05_Ocellar",
//                    "--stack", "v3_acquire",
//                    "--rootDirectory", "/Users/trautmane/Desktop/fibsem_scan_correction",
//                    "--tileId", "22-06-17_080526_0-0-1.1263.0"
//            };
//
//            RenderTileWithTransformsClient.main(testArgs);

            saveTilesWithDifferentScanCorrections();
            
        } catch (final Throwable t) {
            t.printStackTrace();
        }
    }

    /**
     * Loops through tile list and scan correction parameter list to render full scale tiles
     * in logical subdirectories within the root.  Scan correction parameters are also saved
     * in transformSpecList.json files in case they are needed for later reference.
     */
    public static void saveTilesWithDifferentScanCorrections()
            throws IOException {

        final String runTimestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());

        final RenderTileWithTransformsClient.Parameters parameters = new RenderTileWithTransformsClient.Parameters();
        parameters.renderWeb = new RenderWebServiceParameters();
        parameters.renderWeb.baseDataUrl = "http://renderer-dev.int.janelia.org:8080/render-ws/v1";
        parameters.renderWeb.owner = "reiser";
        parameters.renderWeb.project = "Z0422_05_Ocellar";
        parameters.stack = "v3_acquire";

        // TODO: Preibisch - add tileIds as needed
        parameters.tileIds = Arrays.asList(
                "22-06-17_080526_0-0-0.1263.0", "22-06-17_080526_0-0-1.1263.0", // z 1263, row 0, columns 0 and 1
                "22-06-17_081143_0-0-0.1264.0", "22-06-17_081143_0-0-1.1264.0", // z 1264, row 0, columns 0 and 1

                "22-06-18_034043_0-0-0.2097.0",       "22-06-18_034043_0-0-1.2097.0",       // z 2097, row 0, columns 0 and 1
                "22-06-18_125654_0-0-0.patch.2098.0", "22-06-18_125654_0-0-1.patch.2098.0"  // z 2098, row 0, columns 0 and 1 (patched, so really from z 2099)
        );

        // TODO: Preibisch - change path (recommend you keep runTimestamp suffix)
        final String scanCorrectionDir = "/Users/preibischs/Desktop/fibsem_scan_correction_" + runTimestamp;

        // TODO: Preibisch - add/change scan correction parameters as needed (my test_a is just an example)
        final String[][] testSpecificArgs = {
                // subdirectory, scan correction parameters: a * exp(-x/b) + c * exp(-x/d) , last param 0 => x dimension
                {    "original", "  19.4   64.8   24.4   972.0   0"},
                {      "test_a", "1900.4   64.8   24.4   972.0   0"},
        };

        // you should not need to change anything below ...
        for (final String[] testArgs : testSpecificArgs) {

            parameters.rootDirectory = Paths.get(scanCorrectionDir, testArgs[0]).toString();
            FileUtil.ensureWritableDirectory(new File(parameters.rootDirectory));

            parameters.transformFile = Paths.get(parameters.rootDirectory, "transformSpecList.json").toString();

            final LeafTransformSpec transformSpec = new LeafTransformSpec(SEMDistortionTransformA.class.getName(),
                                                                          testArgs[1]);
            FileUtil.saveJsonFile(parameters.transformFile,
                                  Collections.singletonList(transformSpec)); // nest single transform in list

            final RenderTileWithTransformsClient client = new RenderTileWithTransformsClient(parameters);
            client.renderTiles();

        }

    }

}

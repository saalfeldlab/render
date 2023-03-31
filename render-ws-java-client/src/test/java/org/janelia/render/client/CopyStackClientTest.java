package org.janelia.render.client;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.janelia.alignment.util.FileUtil;
import org.janelia.render.client.parameter.CellId;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.ExcludedCellParameters;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link CopyStackClient} class.
 *
 * @author Eric Trautman
 */
public class CopyStackClientTest {

    private final String jsonFileName =
            "test_copy_" + new SimpleDateFormat("yyyyMMddHHmmssSSS").format(new Date()) + ".json";
    private final File testJsonFile = new File(jsonFileName);

    @After
    public void tearDown() {
        if (testJsonFile.exists()) {
            FileUtil.deleteRecursive(testJsonFile);
        }
    }

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new CopyStackClient.Parameters());
    }

    @Test
    public void testExcludedCellParametersParsing() throws Exception {

        final String json =
                "[\n" +
                " { \"cellIds\": [ \"0,0\", \"0,1\", \"0,2\", \"1,0\", \"2,0\" ], \"minZ\": 1, \"maxZ\": 1000 },\n" +
                " { \"cellIds\": [ \"0,0\", \"0,2\", \"1,0\", \"2,0\" ], \"minZ\": 1001, \"maxZ\": 3500 },\n" +
                " { \"cellIds\": [ \"1,0\", \"2,0\" ], \"minZ\": 3501, \"maxZ\": 4608 }\n" +
                "]";

        Files.write(testJsonFile.toPath(), json.getBytes(StandardCharsets.UTF_8));

        final CopyStackClient.Parameters p = new CopyStackClient.Parameters();
        p.parse(new String[]{
                "--baseDataUrl", "http://renderer-dev:8080/render-ws/v1",
                "--owner", "fibsem ",
                "--project", "Z0422_17_VNC_1",
                "--fromStack", "v2_acquire",
                "--toStack", "v2_acquire_trimmed",
                "--excludedCellsJson", testJsonFile.getAbsolutePath(),
                "--keepExisting",
                "--z", "1"
        });
        final ExcludedCellParameters.ExcludedCellList list = p.excludedCells.toList();

        final CellId cellId = new CellId(0, 1);
        double z = 1000.0;
        Assert.assertTrue("cell " + cellId + ", z " + z + " should be excluded",
                          list.isExcludedCell(cellId, z));

        z = 1001.0;
        Assert.assertFalse("cell " + cellId + ", z " + z + " should NOT be excluded",
                           list.isExcludedCell(cellId, z));
    }

    public static void main(final String[] args) {

        final String[] effectiveArgs = new String[] {
                "--baseDataUrl", "http://em-services-1.int.janelia.org:8080/render-ws/v1",
                "--owner", "hess",
                "--project", "wafer_52_cut_00030_to_00039",
                "--fromStack", "slab_045_all_align_t2",
                "--toStack", "slab_045_all_align_t2_mfov_4_center_19",
                "--includeTileIdsWithPattern", "_000004_0[0-1]",
                "--completeToStackAfterCopy"
        };

        CopyStackClient.main(effectiveArgs);
    }

}

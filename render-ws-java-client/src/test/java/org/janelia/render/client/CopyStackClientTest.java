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
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "flyTEM",
                "--project", "FAFB_montage_wobble",
                "--fromStack", "v15_acquire_wobble",
                "--toStack", "v15_acquire_wobble_fix_1246",
                "--replaceLastTransformWithStage", "--completeToStackAfterCopy",
                "--excludedTileIds",
                "151019112742038010.1246.0", "151019112742036011.1246.0", "151019112742034013.1246.0",
                "151019112742028014.1246.0", "151019112742024015.1246.0", "151019112742042025.1246.0",
                "151019112742030026.1246.0", "151019112742028029.1246.0", "151019112742039046.1246.0",
                "151019112742035043.1246.0", "151019112742034044.1246.0", "151019112742034043.1246.0",
                "151019112742029034.1246.0", "151019112742025032.1246.0", "151019112742031036.1246.0",
                "151019112742034059.1246.0", "151019112742049022.1246.0",
                "--z", "1246"
        };

        CopyStackClient.main(effectiveArgs);
    }

}

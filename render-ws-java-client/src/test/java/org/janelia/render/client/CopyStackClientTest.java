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
                "--owner", "hess_wafers_60_61",
                "--project", "w60_serial_360_to_369",
                "--fromStack", "w60_s360_r00_d20_gc_trim",
                "--toStack", "w60_s360_r00_d20_gc_trim_b",
                "--excludedTileIds",
                "w60_magc0160_scan073_m0013_r40_s63", "w60_magc0160_scan073_m0013_r50_s38", "w60_magc0160_scan073_m0013_r51_s62", "w60_magc0160_scan073_m0013_r60_s61", "w60_magc0160_scan073_m0013_r61_s91", "w60_magc0160_scan073_m0013_r69_s60", "w60_magc0160_scan073_m0013_r70_s90", "w60_magc0160_scan073_m0013_r77_s59",
                "w60_magc0160_scan073_m0013_r78_s89", "w60_magc0160_scan073_m0013_r84_s58", "w60_magc0160_scan073_m0013_r85_s88", "w60_magc0160_scan073_m0013_r90_s86", "w60_magc0160_scan073_m0013_r91_s87", "w60_magc0160_scan074_m0013_r40_s63", "w60_magc0160_scan074_m0013_r50_s38", "w60_magc0160_scan074_m0013_r51_s62",
                "w60_magc0160_scan074_m0013_r60_s61", "w60_magc0160_scan074_m0013_r61_s91", "w60_magc0160_scan074_m0013_r69_s60", "w60_magc0160_scan074_m0013_r70_s90", "w60_magc0160_scan074_m0013_r77_s59", "w60_magc0160_scan074_m0013_r78_s89", "w60_magc0160_scan074_m0013_r84_s58", "w60_magc0160_scan074_m0013_r85_s88",
                "w60_magc0160_scan074_m0013_r90_s86", "w60_magc0160_scan074_m0013_r91_s87",
                "w60_magc0160_scan048_m0028_r39_s39", "w60_magc0160_scan048_m0028_r40_s63", "w60_magc0160_scan048_m0028_r49_s20", "w60_magc0160_scan048_m0028_r50_s38", "w60_magc0160_scan048_m0028_r51_s62", "w60_magc0160_scan048_m0028_r59_s37", "w60_magc0160_scan048_m0028_r60_s61", "w60_magc0160_scan048_m0028_r61_s91",
                "w60_magc0160_scan048_m0028_r67_s18", "w60_magc0160_scan048_m0028_r68_s36", "w60_magc0160_scan048_m0028_r69_s60", "w60_magc0160_scan048_m0028_r70_s90", "w60_magc0160_scan048_m0028_r75_s34", "w60_magc0160_scan048_m0028_r76_s35", "w60_magc0160_scan048_m0028_r77_s59", "w60_magc0160_scan048_m0028_r78_s89",
                "w60_magc0160_scan048_m0028_r82_s56", "w60_magc0160_scan048_m0028_r83_s57", "w60_magc0160_scan048_m0028_r84_s58", "w60_magc0160_scan048_m0028_r85_s88", "w60_magc0160_scan048_m0028_r88_s84", "w60_magc0160_scan048_m0028_r89_s85", "w60_magc0160_scan048_m0028_r90_s86", "w60_magc0160_scan048_m0028_r91_s87",
                "w60_magc0160_scan081_m0028_r39_s39", "w60_magc0160_scan081_m0028_r40_s63", "w60_magc0160_scan081_m0028_r49_s20", "w60_magc0160_scan081_m0028_r50_s38", "w60_magc0160_scan081_m0028_r51_s62", "w60_magc0160_scan081_m0028_r59_s37", "w60_magc0160_scan081_m0028_r60_s61", "w60_magc0160_scan081_m0028_r61_s91",
                "w60_magc0160_scan081_m0028_r67_s18", "w60_magc0160_scan081_m0028_r68_s36", "w60_magc0160_scan081_m0028_r69_s60", "w60_magc0160_scan081_m0028_r70_s90", "w60_magc0160_scan081_m0028_r75_s34", "w60_magc0160_scan081_m0028_r76_s35", "w60_magc0160_scan081_m0028_r77_s59", "w60_magc0160_scan081_m0028_r78_s89",
                "w60_magc0160_scan081_m0028_r82_s56", "w60_magc0160_scan081_m0028_r83_s57", "w60_magc0160_scan081_m0028_r84_s58", "w60_magc0160_scan081_m0028_r85_s88", "w60_magc0160_scan081_m0028_r88_s84", "w60_magc0160_scan081_m0028_r89_s85", "w60_magc0160_scan081_m0028_r90_s86", "w60_magc0160_scan081_m0028_r91_s87",
                "w60_magc0160_scan026_m0028_r39_s39", "w60_magc0160_scan026_m0028_r40_s63", "w60_magc0160_scan026_m0028_r49_s20", "w60_magc0160_scan026_m0028_r50_s38", "w60_magc0160_scan026_m0028_r51_s62", "w60_magc0160_scan026_m0028_r59_s37", "w60_magc0160_scan026_m0028_r60_s61", "w60_magc0160_scan026_m0028_r61_s91",
                "w60_magc0160_scan026_m0028_r67_s18", "w60_magc0160_scan026_m0028_r68_s36", "w60_magc0160_scan026_m0028_r69_s60", "w60_magc0160_scan026_m0028_r70_s90", "w60_magc0160_scan026_m0028_r75_s34", "w60_magc0160_scan026_m0028_r76_s35", "w60_magc0160_scan026_m0028_r77_s59", "w60_magc0160_scan026_m0028_r78_s89",
                "w60_magc0160_scan026_m0028_r82_s56", "w60_magc0160_scan026_m0028_r83_s57", "w60_magc0160_scan026_m0028_r84_s58", "w60_magc0160_scan026_m0028_r85_s88", "w60_magc0160_scan026_m0028_r88_s84", "w60_magc0160_scan026_m0028_r89_s85", "w60_magc0160_scan026_m0028_r90_s86", "w60_magc0160_scan026_m0028_r91_s87",
                "w60_magc0160_scan077_m0013_r40_s63", "w60_magc0160_scan077_m0013_r50_s38", "w60_magc0160_scan077_m0013_r51_s62", "w60_magc0160_scan077_m0013_r60_s61", "w60_magc0160_scan077_m0013_r61_s91", "w60_magc0160_scan077_m0013_r69_s60", "w60_magc0160_scan077_m0013_r70_s90", "w60_magc0160_scan077_m0013_r77_s59",
                "w60_magc0160_scan077_m0013_r78_s89", "w60_magc0160_scan077_m0013_r84_s58", "w60_magc0160_scan077_m0013_r85_s88", "w60_magc0160_scan077_m0013_r90_s86", "w60_magc0160_scan077_m0013_r91_s87",
                "w60_magc0160_scan070_m0044_r01_s72", "w60_magc0160_scan070_m0044_r07_s73", "w60_magc0160_scan070_m0044_r14_s74", "w60_magc0160_scan070_m0044_r22_s75", "w60_magc0160_scan071_m0044_r01_s72", "w60_magc0160_scan071_m0044_r07_s73", "w60_magc0160_scan071_m0044_r14_s74", "w60_magc0160_scan071_m0044_r22_s75",
                "w60_magc0160_scan072_m0044_r01_s72", "w60_magc0160_scan072_m0044_r07_s73", "w60_magc0160_scan072_m0044_r14_s74", "w60_magc0160_scan072_m0044_r22_s75",
                "w60_magc0160_scan077_m0021_r36_s03", "w60_magc0160_scan078_m0021_r36_s03", "w60_magc0160_scan079_m0021_r36_s03", "w60_magc0160_scan080_m0021_r36_s03", "w60_magc0160_scan081_m0021_r39_s39",
                "w60_magc0160_scan071_m0019_r65_s16", "w60_magc0160_scan072_m0019_r65_s16", "w60_magc0160_scan073_m0019_r65_s16", "w60_magc0160_scan074_m0019_r65_s16", "w60_magc0160_scan075_m0019_r65_s16",
                "w60_magc0160_scan071_m0007_r18_s24", "w60_magc0160_scan072_m0007_r18_s24", "w60_magc0160_scan073_m0007_r18_s24", "w60_magc0160_scan074_m0007_r18_s24", "w60_magc0160_scan075_m0007_r18_s24",
                "w60_magc0160_scan077_m0044_r01_s72", "w60_magc0160_scan077_m0044_r07_s73", "w60_magc0160_scan077_m0044_r14_s74", "w60_magc0160_scan077_m0044_r22_s75",
                "w60_magc0160_scan077_m0042_r56_s06", "w60_magc0160_scan078_m0042_r56_s06", "w60_magc0160_scan079_m0042_r56_s06", "w60_magc0160_scan080_m0042_r56_s06",
                "w60_magc0160_scan077_m0037_r67_s18", "w60_magc0160_scan078_m0037_r67_s18", "w60_magc0160_scan079_m0037_r67_s18", "w60_magc0160_scan080_m0037_r67_s18",
                "w60_magc0160_scan077_m0020_r60_s61", "w60_magc0160_scan078_m0020_r60_s61", "w60_magc0160_scan079_m0020_r60_s61", "w60_magc0160_scan080_m0020_r60_s61",
                "w60_magc0160_scan077_m0041_r76_s35", "w60_magc0160_scan078_m0041_r76_s35", "w60_magc0160_scan079_m0041_r76_s35", "w60_magc0160_scan080_m0041_r76_s35",
                "w60_magc0160_scan077_m0003_r33_s28", "w60_magc0160_scan078_m0003_r33_s28", "w60_magc0160_scan079_m0003_r33_s28", "w60_magc0160_scan080_m0003_r33_s28",
                "w60_magc0160_scan077_m0051_r32_s49", "w60_magc0160_scan078_m0051_r32_s49", "w60_magc0160_scan079_m0051_r32_s49", "w60_magc0160_scan080_m0051_r32_s49",
                "w60_magc0160_scan077_m0021_r27_s10", "w60_magc0160_scan078_m0021_r27_s10", "w60_magc0160_scan079_m0021_r27_s10", "w60_magc0160_scan080_m0021_r27_s10",
                "w60_magc0160_scan077_m0018_r65_s16", "w60_magc0160_scan078_m0018_r65_s16", "w60_magc0160_scan079_m0018_r65_s16", "w60_magc0160_scan080_m0018_r65_s16",
                "w60_magc0160_scan072_m0037_r23_s48", "w60_magc0160_scan073_m0037_r23_s48", "w60_magc0160_scan074_m0037_r23_s48", "w60_magc0160_scan075_m0037_r23_s48",
                "w60_magc0160_scan077_m0041_r58_s19", "w60_magc0160_scan078_m0041_r58_s19", "w60_magc0160_scan079_m0041_r58_s19", "w60_magc0160_scan080_m0041_r58_s19",
                "w60_magc0160_scan073_m0006_r46_s01", "w60_magc0160_scan074_m0006_r46_s01", "w60_magc0160_scan075_m0006_r46_s01",
                "w60_magc0160_scan073_m0027_r65_s16", "w60_magc0160_scan074_m0027_r65_s16", "w60_magc0160_scan075_m0027_r65_s16",
                "w60_magc0160_scan077_m0018_r64_s31", "w60_magc0160_scan078_m0018_r64_s31", "w60_magc0160_scan079_m0018_r64_s31",
                "w60_magc0160_scan073_m0019_r67_s18", "w60_magc0160_scan074_m0019_r67_s18", "w60_magc0160_scan075_m0019_r67_s18",
                "w60_magc0160_scan076_m0023_r14_s74", "w60_magc0160_scan076_m0023_r23_s48", "w60_magc0160_scan076_m0023_r33_s28",
                "w60_magc0160_scan076_m0007_r20_s41", "w60_magc0160_scan076_m0007_r29_s40",
                "w60_magc0160_scan079_m0046_r57_s07", "w60_magc0160_scan080_m0046_r57_s07",
                "w60_magc0160_scan070_m0048_r27_s10", "w60_magc0160_scan071_m0048_r27_s10",
                "w60_magc0160_scan059_m0029_r48_s08", "w60_magc0160_scan060_m0029_r48_s08",
                "w60_magc0160_scan077_m0042_r47_s02", "w60_magc0160_scan078_m0042_r47_s02",
                "w60_magc0160_scan074_m0027_r79_s81", "w60_magc0160_scan075_m0027_r79_s81",
                "w60_magc0160_scan070_m0011_r15_s47", "w60_magc0160_scan071_m0011_r15_s47",
                "w60_magc0160_scan074_m0017_r91_s87", "w60_magc0160_scan075_m0017_r91_s87",
                "w60_magc0160_scan073_m0042_r71_s80", "w60_magc0160_scan074_m0042_r71_s80",
                "w60_magc0160_scan074_m0036_r35_s04", "w60_magc0160_scan075_m0036_r35_s04",
                "w60_magc0160_scan055_m0048_r27_s10", "w60_magc0160_scan056_m0048_r27_s10",
                "w60_magc0160_scan074_m0021_r68_s36", "w60_magc0160_scan075_m0021_r68_s36",
                "w60_magc0160_scan081_m0041_r34_s13", "w60_magc0160_scan081_m0041_r35_s04",
                "w60_magc0160_scan074_m0048_r27_s10", "w60_magc0160_scan075_m0048_r27_s10",
                "w60_magc0160_scan065_m0048_r27_s10", "w60_magc0160_scan066_m0048_r27_s10",
                "w60_magc0160_scan071_m0006_r57_s07", "w60_magc0160_scan072_m0006_r57_s07",
                "w60_magc0160_scan074_m0026_r42_s50", "w60_magc0160_scan075_m0026_r42_s50",
                "w60_magc0160_scan024_m0034_r64_s31", "w60_magc0160_scan025_m0034_r64_s31",
                "w60_magc0160_scan079_m0003_r15_s47", "w60_magc0160_scan080_m0003_r15_s47",
                "w60_magc0160_scan074_m0036_r36_s03", "w60_magc0160_scan075_m0036_r36_s03",
                "w60_magc0160_scan076_m0018_r30_s64", "w60_magc0160_scan076_m0026_r62_s79",
                "w60_magc0160_scan074_m0027_r63_s52", "w60_magc0160_scan075_m0027_r63_s52",
                "w60_magc0160_scan076_m0036_r02_s71", "w60_magc0160_scan076_m0036_r03_s70", "w60_magc0160_scan080_m0036_r30_s64", "w60_magc0160_scan081_m0036_r21_s65", "w60_magc0160_scan081_m0036_r30_s64",
                "--completeToStackAfterCopy"
        };

        CopyStackClient.main(effectiveArgs);
    }

}

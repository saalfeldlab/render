package org.janelia.alignment.multisem;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link MultiSemUtilities} class.
 *
 * @author Eric Trautman
 */
public class MultiSemUtilitiesTest {

    @Test
    public void testTileIdParsers() {
        final String tileId = "w60_magc0399_scan005_m0013_r46_s01";
        Assert.assertEquals("invalid MagcMfov",
                            "0399_m0013", MultiSemUtilities.getMagcMfovForTileId(tileId));
        Assert.assertEquals("invalid MagcMfovSfov",
                            "0399_m0013_s01", MultiSemUtilities.getMagcMfovSfovForTileId(tileId));
        Assert.assertEquals("invalid MfovSfov",
                            "m0013_s01", MultiSemUtilities.getMfovSfovForTileId(tileId));
        Assert.assertEquals("invalid SFOVIndexForTileId",
                            "01", MultiSemUtilities.getSFOVIndexForTileId(tileId));
        Assert.assertEquals("invalid ScanStringForTileId",
                            "scan005", MultiSemUtilities.getScanStringForTileId(tileId));

        final String manyScanTileId = "w66_magc0000_sc09876_m0005_r65_s16";
        Assert.assertEquals("invalid MagcMfov",
                            "0000_m0005", MultiSemUtilities.getMagcMfovForTileId(manyScanTileId));
        Assert.assertEquals("invalid MagcMfovSfov",
                            "0000_m0005_s16", MultiSemUtilities.getMagcMfovSfovForTileId(manyScanTileId));
        Assert.assertEquals("invalid MfovSfov",
                            "m0005_s16", MultiSemUtilities.getMfovSfovForTileId(manyScanTileId));
        Assert.assertEquals("invalid SFOVIndexForTileId",
                            "16", MultiSemUtilities.getSFOVIndexForTileId(manyScanTileId));
        Assert.assertEquals("invalid ScanStringForTileId",
                            "sc09876", MultiSemUtilities.getScanStringForTileId(manyScanTileId));
    }

    @Test
    public void testMfovRow() {
        // first and last render index of each row
        final int[][] rowBounds = {
                {1, 6}, {7, 13}, {14, 21}, {22, 30}, {31, 40}, {41, 51}, {52, 61}, {62, 70}, {71, 78}, {79, 85}, {86, 91}
        };
        for (int row = 0; row < rowBounds.length; row++) {
            for (final int r : rowBounds[row]) {
                final String tileId = String.format("w61_magc0145_scan004_m0024_r%02d_s67", r);
                Assert.assertEquals("invalid row for " + tileId, row, MultiSemUtilities.getMfovRowForTileId(tileId));
            }
        }
        Assert.assertEquals(10, MultiSemUtilities.getMfovRowForTileId("w66_magc0000_sc09876_m0005_r91_s16"));
    }

}

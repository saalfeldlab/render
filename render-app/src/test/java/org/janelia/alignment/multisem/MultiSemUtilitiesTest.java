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

        final String manyScanTileId = "w66_magc0000_sc09876_m0005_r65_s16";
        Assert.assertEquals("invalid MagcMfov",
                            "0000_m0005", MultiSemUtilities.getMagcMfovForTileId(manyScanTileId));
        Assert.assertEquals("invalid MagcMfovSfov",
                            "0000_m0005_s16", MultiSemUtilities.getMagcMfovSfovForTileId(manyScanTileId));
        Assert.assertEquals("invalid MfovSfov",
                            "m0005_s16", MultiSemUtilities.getMfovSfovForTileId(manyScanTileId));
        Assert.assertEquals("invalid SFOVIndexForTileId",
                            "16", MultiSemUtilities.getSFOVIndexForTileId(manyScanTileId));
    }

    @Test
    public void testGetRowMajorSFOVIndex() {
        // spiral -> row-major permutation of the 91-beam hex layout (see TileReorderingClient's original table)
        Assert.assertEquals("spiral 1 (center) should be row-major 46",
                            46, MultiSemUtilities.getRowMajorSFOVIndex(1));
        Assert.assertEquals("spiral 72 should be row-major 1 (top-left)",
                            1, MultiSemUtilities.getRowMajorSFOVIndex(72));
        Assert.assertEquals("spiral 91 should be row-major 61",
                            61, MultiSemUtilities.getRowMajorSFOVIndex(91));
    }

}

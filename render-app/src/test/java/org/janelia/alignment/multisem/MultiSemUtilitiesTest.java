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
    }

}

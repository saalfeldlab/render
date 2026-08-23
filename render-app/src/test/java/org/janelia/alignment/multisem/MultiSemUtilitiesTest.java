package org.janelia.alignment.multisem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the {@link MultiSemUtilities} class.
 *
 * @author Eric Trautman
 */
public class MultiSemUtilitiesTest {

    @Test
    public void testTileIdParsers() {
        final String tileId = "w60_magc0399_scan005_m0013_r46_s01";
        assertEquals("0399_m0013", MultiSemUtilities.getMagcMfovForTileId(tileId),
                     "invalid MagcMfov");
        assertEquals("0399_m0013_s01", MultiSemUtilities.getMagcMfovSfovForTileId(tileId),
                     "invalid MagcMfovSfov");
        assertEquals("m0013_s01", MultiSemUtilities.getMfovSfovForTileId(tileId),
                     "invalid MfovSfov");
        assertEquals("01", MultiSemUtilities.getSFOVIndexForTileId(tileId),
                     "invalid SFOVIndexForTileId");

        final String manyScanTileId = "w66_magc0000_sc09876_m0005_r65_s16";
        assertEquals("0000_m0005", MultiSemUtilities.getMagcMfovForTileId(manyScanTileId),
                     "invalid MagcMfov");
        assertEquals("0000_m0005_s16", MultiSemUtilities.getMagcMfovSfovForTileId(manyScanTileId),
                     "invalid MagcMfovSfov");
        assertEquals("m0005_s16", MultiSemUtilities.getMfovSfovForTileId(manyScanTileId),
                     "invalid MfovSfov");
        assertEquals("16", MultiSemUtilities.getSFOVIndexForTileId(manyScanTileId),
                     "invalid SFOVIndexForTileId");
    }

}

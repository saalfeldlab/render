package org.janelia.render.client.multisem;

import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.MontageRelativePosition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the {@link MFOVPositionPair} class.
 *
 * @author Eric Trautman
 */
public class MFOVPositionPairTest {

    @Test
    public void testToPositionCanvasId() {
        final CanvasId tilePairId = new CanvasId("1.0",
                                                 "w60_magc0399_scan004_m0013_r46_s01",
                                                 MontageRelativePosition.LEFT);
        final CanvasId positionPairId = MFOVPositionPair.toPositionCanvasId(tilePairId);
        assertEquals("magc0399", positionPairId.getGroupId(), "invalid canvas group id");
        assertEquals("m0013_s01", positionPairId.getId(), "invalid canvas id");
    }

}

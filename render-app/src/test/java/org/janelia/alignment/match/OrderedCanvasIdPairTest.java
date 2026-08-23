package org.janelia.alignment.match;

import org.janelia.alignment.spec.TileBounds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the {@link OrderedCanvasIdPair} class.
 *
 * @author Eric Trautman
 */
public class OrderedCanvasIdPairTest {

    @Test
    public void testNormalization() {

        OrderedCanvasIdPair pair = new OrderedCanvasIdPair(getCanvasId(1), getCanvasId(2), null);
        assertEquals(pair.getP(), getCanvasId(1), "invalid canvasId for p in pair " + pair);
        assertEquals(pair.getQ(), getCanvasId(2), "invalid canvasId for q in pair " + pair);

        pair = new OrderedCanvasIdPair(getCanvasId(9), getCanvasId(5), null);
        assertEquals(pair.getP(), getCanvasId(5), "invalid canvasId for p in pair " + pair);
        assertEquals(pair.getQ(), getCanvasId(9), "invalid canvasId for q in pair " + pair);
    }

    @Test
    public void testWithRelativeMontagePositions() {
        final String tileAId = "tileA";
        final TileBounds boundsA = new TileBounds(tileAId,"1.0", 1.0,
                                                  10.0, 11.0,
                                                  20.0, 21.0);
        final String tileBId = "tileB";
        final TileBounds boundsB = new TileBounds(tileBId, boundsA.getSectionId(), boundsA.getZ(),
                                                  boundsA.getMinX() + 5.0, boundsA.getMinY(),
                                                  boundsA.getMaxX() + 5.0, boundsA.getMaxY());

        final OrderedCanvasIdPair leftRightPair = OrderedCanvasIdPair.withRelativeMontagePositions(boundsA,
                                                                                                   boundsB);
        validatePair("leftRightPair", leftRightPair, tileAId, MontageRelativePosition.LEFT);

        final String sameBoundsTileId = "tileSameBounds";
        final TileBounds sameBounds = new TileBounds(sameBoundsTileId, boundsA.getSectionId(), boundsA.getZ(),
                                                     boundsA.getMinX(), boundsA.getMinY(),
                                                     boundsA.getMaxX(), boundsA.getMaxY());

        final OrderedCanvasIdPair orderedSameBoundsPair = OrderedCanvasIdPair.withRelativeMontagePositions(boundsA,
                                                                                                           sameBounds);
        validatePair("orderedSameBoundsPair", orderedSameBoundsPair, tileAId, MontageRelativePosition.TOP);

        final OrderedCanvasIdPair reversedSameBoundsPair = OrderedCanvasIdPair.withRelativeMontagePositions(sameBounds,
                                                                                                           boundsA);
        assertEquals(orderedSameBoundsPair, reversedSameBoundsPair,
                     "same bounds pairs should be the same");
    }

    private CanvasId getCanvasId(final int tileIndex) {
        return new CanvasId("99.0", "tile-" + tileIndex);
    }

    @SuppressWarnings("SameParameterValue")
    private void validatePair(final String context,
                              final OrderedCanvasIdPair pair,
                              final String expectedPTileId,
                              final MontageRelativePosition expectedPPosition) {
        final CanvasId p = pair.getP();
        assertEquals(expectedPTileId, p.getId(),
                     context + ", invalid p.id for pair " + pair);
        assertEquals(expectedPPosition, p.getRelativePosition(),
                     context + ", invalid p.relativePosition for " + pair);
    }

}

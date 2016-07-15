package org.janelia.alignment.match;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link OrderedCanvasIdPair} class.
 *
 * @author Eric Trautman
 */
public class OrderedCanvasIdPairTest {

    @Test
    public void testNormalization() {

        OrderedCanvasIdPair pair = new OrderedCanvasIdPair(getCanvasId(1), getCanvasId(2));
        Assert.assertEquals("invalid canvasId for p in pair " + pair, pair.getP(), getCanvasId(1));
        Assert.assertEquals("invalid canvasId for q in pair " + pair, pair.getQ(), getCanvasId(2));

        pair = new OrderedCanvasIdPair(getCanvasId(9), getCanvasId(5));
        Assert.assertEquals("invalid canvasId for p in pair " + pair, pair.getP(), getCanvasId(5));
        Assert.assertEquals("invalid canvasId for q in pair " + pair, pair.getQ(), getCanvasId(9));
    }

    private CanvasId getCanvasId(final int tileIndex) {
        return new CanvasId("99.0", "tile-" + tileIndex);
    }

}

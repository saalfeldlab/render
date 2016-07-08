package org.janelia.alignment.spec;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link TileIdPair} class.
 *
 * @author Eric Trautman
 */
public class TileIdPairTest {

    @Test
    public void testNormalization() {

        TileIdPair pair = new TileIdPair(getTileId(1), getTileId(2));
        Assert.assertEquals("invalid tileId for p in pair " + pair, pair.getPId(), getTileId(1));
        Assert.assertEquals("invalid tileId for q in pair " + pair, pair.getQId(), getTileId(2));

        pair = new TileIdPair(getTileId(9), getTileId(5));
        Assert.assertEquals("invalid tileId for p in pair " + pair, pair.getPId(), getTileId(5));
        Assert.assertEquals("invalid tileId for q in pair " + pair, pair.getQId(), getTileId(9));
    }

    @Test
    public void testGetTileIdPairs() {

        final List<TileBounds> tileBoundsList = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tileBoundsList.add(getTileBounds(i));
        }

        final Set<TileIdPair> comboSet = TileIdPair.getTileIdPairs(tileBoundsList.get(0), tileBoundsList);
        Assert.assertEquals("incorrect number of combinations in " + comboSet,
                            tileBoundsList.size() - 1, comboSet.size());
    }

    private String getTileId(final int tileIndex) {
        return "tile-" + tileIndex;
    }

    private TileBounds getTileBounds(final int tileIndex) {
        return new TileBounds(getTileId(tileIndex), null, null, null, null, null, null);
    }

}

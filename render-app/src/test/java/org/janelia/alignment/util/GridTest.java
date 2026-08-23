package org.janelia.alignment.util;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests the {@link Grid} class.
 */
public class GridTest {

    @Test
    public void testCreate() {
        final int tileWidth = 4096;
        final int tileHeight = 4096;
        final int[] blockSize = { 128, 128, 64 };

        final long[] dimensions = { 34839, 19362, 29491};
        final int[] gridBlockSize = { tileWidth, tileHeight, blockSize[2] };

        final List<Grid.Block> blockList = Grid.create(dimensions, gridBlockSize, blockSize);

        assertNotNull(blockList, "block list is null");
        assertEquals(20745, blockList.size(), "invalid number of blocks");

        final Grid.Block firstBlock = blockList.get(0);
        assertNotNull(firstBlock, "first block is null");
        assertEquals(3, firstBlock.numDimensions(),
                     "first block has invalid number of dimensions");
        for (int d = 0; d < 3; ++d) {
            assertEquals(gridBlockSize[d], firstBlock.dimension(d),
                         "first block has invalid dimension " + d);
        }
    }

}

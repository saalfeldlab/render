package org.janelia.alignment.util;

import java.util.List;

import org.junit.Assert;
import org.junit.Test;

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

        Assert.assertNotNull("block list is null", blockList);
        Assert.assertEquals("invalid number of blocks", 20745, blockList.size());

        final Grid.Block firstBlock = blockList.get(0);
        Assert.assertNotNull("first block is null", firstBlock);
        Assert.assertEquals("first block has invalid number of dimensions",
                            3, firstBlock.numDimensions());
        for (int d = 0; d < 3; ++d) {
            Assert.assertEquals("first block has invalid dimension " + d,
                                gridBlockSize[d], firstBlock.dimension(d));
        }
    }

}

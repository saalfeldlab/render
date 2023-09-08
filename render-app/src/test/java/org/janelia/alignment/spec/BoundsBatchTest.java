package org.janelia.alignment.spec;

import java.util.List;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link BoundsBatch} class.
 *
 * @author Eric Trautman
 */
public class BoundsBatchTest {

    @Test
    public void testBatchByAdjacentZThenXY() {

        validateBatch("offset stack",
                      new Bounds(-50.0, -100.0, 1.0, 180.0, 70.0, 555.0),
                      8,
                      8,
                      29,
                      29,
                      18,
                      false);

        validateBatch("small stack",
                      new Bounds(0.0, 0.0, 0.0, 10.0, 20.0, 2.0),
                      2,
                      1,
                      30,
                      4,
                      4,
                      true);
    }


    private void validateBatch(final String testContext,
                               final Bounds stackBounds,
                               final int rowCount,
                               final int columnCount,
                               final int requestedBatchCount,
                               final int expectedBatchCount,
                               final int numberOfBatchesWithAllCells,
                               final boolean printDebugInfo) {

        final List<BoundsBatch> batchList =
                BoundsBatch.batchByAdjacentZThenXY(stackBounds, rowCount, columnCount, requestedBatchCount);

        Assert.assertEquals(testContext + ": invalid number of batches", expectedBatchCount, batchList.size());

        Assert.assertEquals(testContext + ": batches with all cells should have same total area",
                            batchList.get(0).getTotalArea(),
                            batchList.get(numberOfBatchesWithAllCells-1).getTotalArea(),
                            0.1);

        if (numberOfBatchesWithAllCells < batchList.size()) {
            Assert.assertEquals(testContext + ": batches with reduced cells should have same total area",
                                batchList.get(numberOfBatchesWithAllCells).getTotalArea(),
                                batchList.get(batchList.size() - 1).getTotalArea(),
                                0.1);
        }

        if (printDebugInfo) {
            for (int i = 0; i < batchList.size(); i++) {
                System.out.println("  batch " + i);
                final BoundsBatch boundsBatch = batchList.get(i);
                for (final Bounds bounds : boundsBatch.getList()) {
                    System.out.println("    " + bounds);
                }
                System.out.printf("          totalArea = %20.0f%n", boundsBatch.getTotalArea());
            }
        }

    }
}

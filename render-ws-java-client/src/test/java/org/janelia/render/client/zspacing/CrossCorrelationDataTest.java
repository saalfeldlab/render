package org.janelia.render.client.zspacing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.real.DoubleType;

/**
 * Tests the {@link CrossCorrelationData} class.
 *
 * @author Eric Trautman
 */
public class CrossCorrelationDataTest {

    @Test
    public void testToMatrix() {
        final int layerCount = 10;
        final int comparisonRange = 2;
        final CrossCorrelationData ccData = new CrossCorrelationData(layerCount, comparisonRange, 0);

        final int fromLayerIndex = 3;
        final int toLayerIndex = 5;
        final double correlationValue = 0.98;

        ccData.set(fromLayerIndex, toLayerIndex, correlationValue);

        final RandomAccessibleInterval<DoubleType> matrix = ccData.toMatrix();

//        printMatrix(matrix, ccData.getLayerCount());

        validateMatrix(0, 0, matrix, 1.0);
        validateMatrix(0, 1, matrix, 0.0);
        validateMatrix(fromLayerIndex, toLayerIndex, matrix, correlationValue);
        validateMatrix(toLayerIndex, fromLayerIndex, matrix, correlationValue);
    }

    @Test
    public void testMerge() {

        final int layerCount = 10;
        final int comparisonRange = 2;
        final int overlapLayerCount = layerCount + comparisonRange;
        final List<Integer> relativeFromLayerIndexes = Arrays.asList(0, 1, 5); // first 2 layers and one in the middle

        // reverse ordered batch list with overlapping layers
        final List<CrossCorrelationData> unsortedBatches = new ArrayList<>();
        unsortedBatches.add(new CrossCorrelationData(layerCount, comparisonRange, (layerCount*4)));
        unsortedBatches.add(new CrossCorrelationData(overlapLayerCount, comparisonRange, (layerCount*3)));
        unsortedBatches.add(new CrossCorrelationData(overlapLayerCount, comparisonRange, (layerCount*2)));
        unsortedBatches.add(new CrossCorrelationData(overlapLayerCount, comparisonRange, layerCount));

        final int batchCount = unsortedBatches.size();
        final List<Double> sortedBatchCorrelationValues = new ArrayList<>();

        for (int b = 0; b < batchCount; b++) {
            final int sortedBatchNumber = batchCount - b - 1;
            final CrossCorrelationData ccData = unsortedBatches.get(sortedBatchNumber);
            final double correlation = (double) (b + 1) / (batchCount + 1);
            sortedBatchCorrelationValues.add(correlation);
            relativeFromLayerIndexes.forEach(i -> ccData.set(i, (i+1), correlation));
        }

        final CrossCorrelationData mergedDataSet = CrossCorrelationData.merge(unsortedBatches);

        Assert.assertEquals("bad merged comparison range",
                            comparisonRange, mergedDataSet.getComparisonRange());
        Assert.assertEquals("bad merged first layer offset",
                            layerCount, mergedDataSet.getFirstLayerOffset());
        Assert.assertEquals("bad merged layer count",
                            (layerCount * batchCount), mergedDataSet.getLayerCount());
        
        final RandomAccessibleInterval<DoubleType> mergedMatrix = mergedDataSet.toMatrix();

        for (int b = 0; b < batchCount; b++) {
            final int firstX = b * layerCount;
            final double expectedCorrelation = sortedBatchCorrelationValues.get(b);
            relativeFromLayerIndexes.forEach(i -> {
                final int x = firstX + i;
                final int y = x + 1;
                validateMatrix(x, y, mergedMatrix, expectedCorrelation);
            });
        }
    }

    private void validateMatrix(final int x,
                                final int y,
                                final RandomAccessibleInterval<DoubleType> matrix,
                                final double expectedCorrelationValue) {
        Assert.assertEquals("invalid correlation value for (" + x + "," + y + ")",
                            expectedCorrelationValue, matrix.getAt(x, y).get(), 0.01);
    }

    @SuppressWarnings("unused")
    private void printMatrix(final RandomAccessibleInterval<DoubleType> matrix,
                             final int layerCount) {
        for (int x = 0; x < layerCount; x++) {
            for (int y = 0; y < layerCount; y++) {
                System.out.printf("%4.2f  ", matrix.getAt(x, y).get());
            }
            System.out.println();
        }
    }

    public static void main(final String[] args) {

        final int layerCount = 400;
        final int comparisonRange = 10;

        final CrossCorrelationData ccData = new CrossCorrelationData(layerCount, comparisonRange, 0);

        for (int fromLayerIndex = 0; fromLayerIndex < layerCount; fromLayerIndex++) {

            for (int toLayerIndex = fromLayerIndex + 1;
                 toLayerIndex - fromLayerIndex <= comparisonRange && toLayerIndex < layerCount;
                 toLayerIndex++) {

                // set to inverse similarity so it is easier to see
                final double value = (toLayerIndex - fromLayerIndex) / (double) comparisonRange;

                ccData.set(fromLayerIndex, toLayerIndex, value);
            }
        }

        final RandomAccessibleInterval<DoubleType> crossCorrelationMatrix = ccData.toMatrix();

        ImageJFunctions.show(crossCorrelationMatrix);
    }
    
}

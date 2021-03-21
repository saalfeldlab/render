package org.janelia.render.client.zspacing;

import java.util.ArrayList;
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
        final int relativeFromLayerIndex = 2;

        final int batchCount = 4;
        final List<CrossCorrelationData> ccDataBatches = new ArrayList<>();
        final double[] batchCorrelationValues = new double[batchCount];
        for (int b = batchCount - 1; b >= 0; b--) {
            final CrossCorrelationData ccData =
                    new CrossCorrelationData(layerCount, comparisonRange, (b + 1) * layerCount);
            batchCorrelationValues[b] = (double) (b + 1) / (batchCount + 1);
            ccData.set(relativeFromLayerIndex, (relativeFromLayerIndex + 1), batchCorrelationValues[b]);
            ccDataBatches.add(ccData);
        }

        final CrossCorrelationData mergedDataSet = CrossCorrelationData.merge(ccDataBatches);

        Assert.assertEquals("bad merged comparison range",
                            comparisonRange, mergedDataSet.getComparisonRange());
        Assert.assertEquals("bad merged first layer offset",
                            layerCount, mergedDataSet.getFirstLayerOffset());
        Assert.assertEquals("bad merged layer count",
                            (layerCount * batchCount), mergedDataSet.getLayerCount());
        
        final RandomAccessibleInterval<DoubleType> mergedMatrix = mergedDataSet.toMatrix();

        for (int b = 0; b < batchCount; b++) {
            final int x = (b * layerCount) + relativeFromLayerIndex;
            final int y = x + 1;
            validateMatrix(x, y, mergedMatrix, batchCorrelationValues[b]);
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

    public static void main2(final String[] args) {

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

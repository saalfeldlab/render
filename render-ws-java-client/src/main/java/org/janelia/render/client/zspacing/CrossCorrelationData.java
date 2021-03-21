package org.janelia.render.client.zspacing;

import java.io.Serializable;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.imagej.Extents;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.view.Views;

/**
 * Container for layer cross correlation values that optimizes storage and
 * supports merging of concurrently derived data while providing
 * a full matrix view (see {@link #toMatrix()}) for up to 2^31-1 layers.
 *
 * @author Eric Trautman
 */
public class CrossCorrelationData
        implements Serializable {

    private final int layerCount;
    private final int comparisonRange;
    private final int firstLayerOffset;

    // Two dimensional array allows for support of up to 2^31-1 layers
    // and minimizes storage by only tracking values within the comparison range.
    private final double[][] data;

    /**
     * @param  layerCount        total number of layers being compared for this data set.
     * @param  comparisonRange   number of adjacent layers being compared.
     * @param  firstLayerOffset  offset of first layer in this data set relative to a larger encompassing
     *                           data set (used when chunking large data sets and processing in parallel).
     */
    public CrossCorrelationData(final int layerCount,
                                final int comparisonRange,
                                final int firstLayerOffset)
            throws IllegalArgumentException {

        if (layerCount < 1) {
            throw new IllegalArgumentException("layerCount must be positive");
        } else if (comparisonRange < 1) {
            throw new IllegalArgumentException("comparisonRange must be positive");
        } else if (firstLayerOffset < 0) {
            throw new IllegalArgumentException("firstLayerOffset must not be negative");
        }

        this.layerCount = layerCount;
        this.comparisonRange = comparisonRange;
        this.firstLayerOffset = firstLayerOffset;
        this.data = new double[layerCount][comparisonRange];
    }

    public int getLayerCount() {
        return layerCount;
    }

    public int getComparisonRange() {
        return comparisonRange;
    }

    public int getFirstLayerOffset() {
        return firstLayerOffset;
    }

    /**
     * Sets the correlation value for the specified layer pair.
     *
     * @param  fromLayerIndex  index of the lesser layer in this data set.
     * @param  toLayerIndex    index of the greater layer in this data set.
     * @param  value           correlation value.
     *
     * @throws IllegalArgumentException
     *   if any specified index is out of range.
     */
    public void set(final int fromLayerIndex,
                    final int toLayerIndex,
                    final double value)
            throws IllegalArgumentException {

        final int maxLayerIndex = layerCount - 1;
        if ((fromLayerIndex < 0) || (fromLayerIndex > maxLayerIndex)) {
            throw new IllegalArgumentException(
                    "fromLayerIndex " + fromLayerIndex + " must be between 0 and " + maxLayerIndex);
        }

        final int toLayerDelta = toLayerIndex - fromLayerIndex;

        if ((toLayerDelta < 1) || (toLayerDelta > comparisonRange) || (toLayerIndex > maxLayerIndex)) {
            final int min = fromLayerIndex + 1;
            final int max = Math.min((min + comparisonRange - 1), maxLayerIndex);
            throw new IllegalArgumentException(
                    "with fromLayerIndex " + fromLayerIndex +
                    ", toLayerIndex " + toLayerIndex + " must be between " + min + " and " + max);
        }

        data[fromLayerIndex][toLayerDelta-1] = value;
    }

    /**
     * @return a view of this data set's correlation values as a matrix.
     */
    public RandomAccessibleInterval<DoubleType> toMatrix() {

        final FunctionRandomAccessible<DoubleType> randomAccessible = new FunctionRandomAccessible<>(
                2,
                (location, value) -> {

                    final int x = location.getIntPosition(0);
                    final int y = location.getIntPosition(1);

                    final int fromLayerIndex; // always the smaller index
                    final int toLayerDelta;   // always positive
                    if (x > y) {
                        fromLayerIndex = y;
                        toLayerDelta = x - y;
                    } else {
                        fromLayerIndex = x;
                        toLayerDelta = y - x;
                    }

                    if (toLayerDelta == 0) {
                        value.set(1.0);
                    } else if (toLayerDelta <= comparisonRange) {
                        value.set(data[fromLayerIndex][toLayerDelta-1]);
                    } else {
                        value.set(0.0);
                    }
                },
                DoubleType::new);

        final Interval interval = new Extents(new long[] {layerCount, layerCount});

        return Views.interval(randomAccessible, interval);
    }

    @Override
    public String toString() {
        return "{\"firstLayerOffset\": " + firstLayerOffset +
               ", \"layerCount\": " + layerCount +
               ", \"comparisonRange\": " + comparisonRange +
               '}';
    }

    public static CrossCorrelationData merge(final List<CrossCorrelationData> dataSets) {

        final CrossCorrelationData mergedDataSet;

        if (dataSets.size() > 1) {

            final List<CrossCorrelationData> sortedDataSets = dataSets.stream()
                    .sorted(Comparator.comparingInt(o -> o.firstLayerOffset))
                    .collect(Collectors.toList());

            final CrossCorrelationData firstDataSet = sortedDataSets.get(0);
            final CrossCorrelationData lastDataSet = sortedDataSets.get(sortedDataSets.size() - 1);

            final int mergedFirstLayerOffset = firstDataSet.firstLayerOffset;
            final int mergedLayerCount = lastDataSet.firstLayerOffset + lastDataSet.layerCount - mergedFirstLayerOffset;
            final int comparisonRange = firstDataSet.comparisonRange;

            mergedDataSet = new CrossCorrelationData(mergedLayerCount, comparisonRange, mergedFirstLayerOffset);

            final Set<Integer> firstLayerOffsetValues = new HashSet<>();
            int priorMergedLastLayerOffset = -1;

            for (final CrossCorrelationData dataSet : sortedDataSets) {

                if (comparisonRange != dataSet.comparisonRange) {
                    throw new IllegalArgumentException(
                            firstDataSet + " has different comparison range from " + dataSet);
                }

                if (! firstLayerOffsetValues.add(dataSet.firstLayerOffset)) {
                    throw new IllegalArgumentException(
                            "two data sets have the same first layer offset: " + dataSet.firstLayerOffset);
                }

                final int mergedLayerOffset = dataSet.firstLayerOffset - mergedFirstLayerOffset;

                if (mergedLayerOffset < priorMergedLastLayerOffset) {
                    throw new IllegalArgumentException(
                            "data set " + dataSet + " has overlapping layers with prior data set");
                }

                for (int layerIndex = 0; layerIndex < dataSet.layerCount; layerIndex++) {
                    final int mergedLayerIndex = layerIndex + mergedLayerOffset;
                    System.arraycopy(dataSet.data[layerIndex],
                                     0, mergedDataSet.data[mergedLayerIndex],
                                     0, dataSet.comparisonRange);
                }

                priorMergedLastLayerOffset = mergedLayerOffset + dataSet.layerCount;

            }

        } else {
            throw new IllegalArgumentException("need at least two data sets to merge");
        }

        return mergedDataSet;
    }
}

package org.janelia.render.client.zspacing;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvStackSource;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
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

    public static void main(final String[] args) throws Exception {

        final String base = "/nrs/flyem/render/z_corr/Z0720_07m_BR";

        final String tabStackRun = "Sec39/v1_acquire_trimmed_sp1/run_20210323_132057_278_z_corr";

        final Path batchDirPath = Paths.get(base,
                                            tabStackRun,
                                            CrossCorrelationData.DEFAULT_BATCHES_DIR_NAME);

        final List<CrossCorrelationData> ccDataList = loadSmallBatchData(batchDirPath);
//        final List<CrossCorrelationData> ccDataList = loadAllBatchData(batchDirPath);

        final CrossCorrelationData ccData = CrossCorrelationData.merge(ccDataList);

        final RandomAccessibleInterval<DoubleType> crossCorrelationMatrix = ccData.toMatrix();

        final RandomAccessibleInterval<DoubleType> stretched = 
        		Converters.convert( crossCorrelationMatrix, (i,o) -> o.setReal( i.get() * 65535 ), new DoubleType() );

        // TODO: ask SP how to set maxIntensity to 1 (and maybe zoom in) by default here
        BdvStackSource<?> bdv = BdvFunctions.show(crossCorrelationMatrix, tabStackRun);
        bdv.setDisplayRange(0, 1);
        bdv.setDisplayRangeBounds(0, 2);
    }

    private static List<CrossCorrelationData> loadAllBatchData(final Path batchDirPath)
            throws IOException {
        return CrossCorrelationData.loadCrossCorrelationDataFiles(batchDirPath,
                                                                  CrossCorrelationData.DEFAULT_DATA_FILE_NAME,
                                                                  2);
    }

    private static List<CrossCorrelationData> loadSmallBatchData(final Path batchDirPath) {
        final List<CrossCorrelationData> ccDataList = new ArrayList<>();
                final List<Path> batchDirNames = Arrays.asList(
                Paths.get(batchDirPath.toString(), "z_000001.0_to_000060.0"),
                Paths.get(batchDirPath.toString(), "z_000051.0_to_000120.0")
        );
        for (final Path p : batchDirNames) {
            final Path f = Paths.get(p.toString(), CrossCorrelationData.DEFAULT_DATA_FILE_NAME);
            ccDataList.add(CrossCorrelationData.loadCrossCorrelationDataFile(f));
        }
        return ccDataList;
    }
}

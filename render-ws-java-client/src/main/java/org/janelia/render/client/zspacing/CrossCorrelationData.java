package org.janelia.render.client.zspacing;

import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    // required for JSON deserialization
    @SuppressWarnings("unused")
    private CrossCorrelationData() {
        this.layerCount = -1;
        this.comparisonRange = -1;
        this.firstLayerOffset = -1;
        this.data = new double[0][0];
    }

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

    public List<Integer> getPoorCorrelationWithNextIndexes(final double poorCorrelationThreshold) {
        final List<Integer> poorCorrelationIndexList = new ArrayList<>();
        for (int zIndex = 0; zIndex < data.length - 1; zIndex++) {
            final double correlationWithNextLayer = data[zIndex][0];
            if (correlationWithNextLayer <= poorCorrelationThreshold) {
                poorCorrelationIndexList.add(zIndex);
            }
        }

        LOG.info("getPoorCorrelationWithNextIndexes: returning {} indexes for layers with correlation <= {}",
                 poorCorrelationIndexList.size(), poorCorrelationThreshold);

        return poorCorrelationIndexList;
    }

    public double getCrossCorrelationValue(final int pIndex,
                                           final int qIndex) {
        return data[pIndex][qIndex];
    }

    @Override
    public String toString() {
        return "{\"firstLayerOffset\": " + firstLayerOffset +
               ", \"layerCount\": " + layerCount +
               ", \"comparisonRange\": " + comparisonRange +
               '}';
    }

    /**
     * @param  dataSets  list of data sets to merge.
     *
     * @return single data set merged from specified list of data sets.
     *
     * @throws IllegalArgumentException
     *   if any inconsistencies are found between the data sets to be merged.
     *   Data sets may (and typically should) overlap, but each data set must have a unique first layer offset.
     */
    public static CrossCorrelationData merge(final List<CrossCorrelationData> dataSets)
            throws IllegalArgumentException {

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

                for (int layerIndex = 0; layerIndex < dataSet.layerCount; layerIndex++) {
                    final int mergedLayerIndex = layerIndex + mergedLayerOffset;
                    System.arraycopy(dataSet.data[layerIndex],
                                     0, mergedDataSet.data[mergedLayerIndex],
                                     0, dataSet.comparisonRange);
                }

            }

        } else {
            throw new IllegalArgumentException("need at least two data sets to merge");
        }

        return mergedDataSet;
    }

    /**
     * @param  path  path of .json, .gz, or .zip file containing JSON serialization.
     *
     * @return data parsed from the specified file.
     */
    public static CrossCorrelationData loadCrossCorrelationDataFile(final Path path) {
        final CrossCorrelationData ccData;
        try (final Reader reader = FileUtil.DEFAULT_INSTANCE.getExtensionBasedReader(path.toString())) {
            ccData = CrossCorrelationData.fromJson(reader);
        } catch (final Exception e) {
            throw new RuntimeException("failed to load data from " + path, e);
        }

        LOG.info("loadCrossCorrelationDataFile: loaded data for {} layers from {}",
                 ccData.layerCount, path);

        return ccData;
    }

    /**
     * @param  parentDirectoryPath  path of parent directory containing JSON data files.
     * @param  dataFileName         common name of all JSON data files (typically nested within subdirectories).
     * @param  searchDepth          number of subdirectories to search for data files.
     *
     * @return list of data sets parsed.
     *
     * @throws IllegalArgumentException
     *   if appropriate files cannot be found.
     *
     * @throws IOException
     *   if there are any problems reading file data.
     */
    public static List<CrossCorrelationData> loadCrossCorrelationDataFiles(final Path parentDirectoryPath,
                                                                           final String dataFileName,
                                                                           final int searchDepth)
            throws IllegalArgumentException, IOException {

        if (! parentDirectoryPath.toFile().exists()) {
            throw new IllegalArgumentException("cannot find " + parentDirectoryPath);
        }

        final List<CrossCorrelationData> dataSets = new ArrayList<>();
        try (final Stream<Path> stream = Files.walk(parentDirectoryPath, searchDepth)) {
            stream.filter(f -> f.getFileName().toString().equals(dataFileName))
                    .forEach(p -> {
                        final CrossCorrelationData ccData = loadCrossCorrelationDataFile(p);
                        dataSets.add(ccData);
                    });
        }

        if (dataSets.size() < 1) {
            throw new IllegalArgumentException("no " + dataFileName + " files found in " + parentDirectoryPath);
        }

        LOG.info("loadCrossCorrelationDataFiles: returning {} data sets found in {}",
                 dataSets.size(), parentDirectoryPath);

        return dataSets;
    }

    public static final String DEFAULT_DATA_FILE_NAME = "cc_data.json.gz";
    public static final String DEFAULT_BATCHES_DIR_NAME = "cc_batches";

    public static CrossCorrelationData fromJson(final Reader json) {
        return JSON_HELPER.fromJson(json);
    }

    private static final Logger LOG = LoggerFactory.getLogger(CrossCorrelationData.class);

    private static final JsonUtils.Helper<CrossCorrelationData> JSON_HELPER =
            new JsonUtils.Helper<>(CrossCorrelationData.class);

}

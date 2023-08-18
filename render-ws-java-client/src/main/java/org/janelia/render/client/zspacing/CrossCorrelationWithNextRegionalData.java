package org.janelia.render.client.zspacing;

import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Container for regional cross correlation between two adjacent layers.
 *
 * @author Eric Trautman
 */
@SuppressWarnings({"FieldCanBeLocal", "unused"})
public class CrossCorrelationWithNextRegionalData
        implements Serializable {

    private final Double pZ;
    private final Double qZ;
    private final String layerUrlPattern;
    private final Double layerCorrelation;
    private final double[][] regionalCorrelation;

    public CrossCorrelationWithNextRegionalData() {
        this(null, null, null, null, 0, 0);
    }

    public CrossCorrelationWithNextRegionalData(final Double pZ,
                                                final Double qZ,
                                                final String layerUrlPattern,
                                                final Double layerCorrelation,
                                                final int numberOfRegionRows,
                                                final int numberOfRegionColumns) {
        this.pZ = pZ;
        this.qZ = qZ;
        this.layerUrlPattern = layerUrlPattern;
        this.layerCorrelation = layerCorrelation;
        this.regionalCorrelation = new double[numberOfRegionRows][numberOfRegionColumns];
    }

    public Double getpZ() {
        return pZ;
    }

    public Double getqZ() {
        return qZ;
    }

    public Double getLayerCorrelation() {
        return layerCorrelation;
    }

    public double[][] getRegionalCorrelation() {
        return regionalCorrelation;
    }

    public int getRegionalRowCount() {
        return regionalCorrelation.length;
    }

    public int getRegionalColumnCount() {
        return regionalCorrelation.length == 0 ? 0 : regionalCorrelation[0].length;
    }

    public void setValue(final int row,
                         final int column,
                         final double value) {
        regionalCorrelation[row][column] = value;
    }

    public static final String DEFAULT_DATA_FILE_NAME = "poor_cc_regional_data.json.gz";

    public static List<CrossCorrelationWithNextRegionalData> loadDataFile(final Path path) {
        final List<CrossCorrelationWithNextRegionalData> list;
        try (final Reader reader = FileUtil.DEFAULT_INSTANCE.getExtensionBasedReader(path.toString())) {
            list = fromJsonArray(reader);
        } catch (final Exception e) {
            throw new RuntimeException("failed to load data from " + path, e);
        }

        LOG.info("loadDataFile: loaded data for {} layers from {}",
                 list.size(), path);

        return list;
    }

    public static List<CrossCorrelationWithNextRegionalData> loadDataDirectory(final Path rootPath)
            throws IOException {

        LOG.info("loadDataDirectory: entry, rootPath={}", rootPath);

        final List<Path> pathList;
        try (final Stream<Path> stream = Files.walk(rootPath)) {
            pathList = stream.map(Path::normalize)
                    .filter(path -> Files.isRegularFile(path) && DEFAULT_DATA_FILE_NAME.equals(path.toFile().getName()))
                    .collect(Collectors.toList());
        }

        LOG.info("loadDataDirectory: found {} {} files", pathList.size(), DEFAULT_DATA_FILE_NAME);

        final Map<Double, CrossCorrelationWithNextRegionalData> pZToDataMap = new HashMap<>();
        for (final Path path : pathList) {
            for (final CrossCorrelationWithNextRegionalData data : loadDataFile(path)) {
                pZToDataMap.put(data.pZ, data);
            }
        }

        LOG.info("loadDataDirectory: returning data for {} z layers", pZToDataMap.size());

        return pZToDataMap.values().stream().sorted(Comparator.comparing(d -> d.pZ)).collect(Collectors.toList());
    }

    public static List<CrossCorrelationWithNextRegionalData> fromJsonArray(final Reader reader) {
        try {
            return Arrays.asList(JsonUtils.MAPPER.readValue(reader, CrossCorrelationWithNextRegionalData[].class));
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(CrossCorrelationWithNextRegionalData.class);

    private static final JsonUtils.Helper<CrossCorrelationWithNextRegionalData> JSON_HELPER =
            new JsonUtils.Helper<>(CrossCorrelationWithNextRegionalData.class);

}

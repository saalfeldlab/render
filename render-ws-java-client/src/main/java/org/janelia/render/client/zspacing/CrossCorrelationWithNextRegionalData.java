package org.janelia.render.client.zspacing;

import java.io.Serializable;

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
    @SuppressWarnings("MismatchedReadAndWriteOfArray")
    private final double[][] regionalCorrelation;

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

    public void setValue(final int row,
                         final int column,
                         final double value) {
        regionalCorrelation[row][column] = value;
    }

    public static final String DEFAULT_DATA_FILE_NAME = "poor_cc_regional_data.json.gz";
}

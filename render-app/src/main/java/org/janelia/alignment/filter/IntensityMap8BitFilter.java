package org.janelia.alignment.filter;

import java.util.HashMap;
import java.util.Map;

public abstract class IntensityMap8BitFilter
        implements Filter {

    protected int numberOfRegionRows;
    protected int numberOfRegionColumns;
    protected int coefficientsPerRegion;
    protected double[][] coefficients;

    public IntensityMap8BitFilter(final int numberOfRegionRows,
                                  final int numberOfRegionColumns,
                                  final int coefficientsPerRegion,
                                  final double[][] coefficients) {
        this.numberOfRegionRows = numberOfRegionRows;
        this.numberOfRegionColumns = numberOfRegionColumns;
        this.coefficientsPerRegion = coefficientsPerRegion;
        this.coefficients = coefficients;
    }

    public int getNumberOfRegionRows() {
        return numberOfRegionRows;
    }
    
    public double[][] getCoefficients() {
        return coefficients;
    }

    @Override
    public void init(final Map<String, String> params) {
        final String[] values = Filter.getCommaSeparatedStringParameter(DATA_STRING_NAME, params);
        if (values.length < 4) {
            throw new IllegalArgumentException(DATA_STRING_NAME +
                                               " must have pattern <numberOfRegionRows>,<numberOfRegionColumns>,<coefficientsPerRegion>,[coefficient]...");
        }
        this.numberOfRegionRows = Integer.parseInt(values[0]);
        this.numberOfRegionColumns = Integer.parseInt(values[1]);
        this.coefficientsPerRegion = Integer.parseInt(values[2]);
        final int numberOfRegions = this.numberOfRegionRows * this.numberOfRegionColumns;

        final int expectedNumberOfCoefficients = numberOfRegions * this.coefficientsPerRegion;
        final int actualNumberOfCoefficients = values.length - 3;
        if (actualNumberOfCoefficients != expectedNumberOfCoefficients) {
            throw new IllegalArgumentException(DATA_STRING_NAME + " contains " + actualNumberOfCoefficients +
                                               " coefficient values instead of " + expectedNumberOfCoefficients);
        }

        this.coefficients = new double[numberOfRegions][this.coefficientsPerRegion];
        int region = 0;
        for (int i = 3; i < values.length; i+=this.coefficientsPerRegion) {
            this.coefficients[region][0] = Double.parseDouble(values[i]);
            this.coefficients[region][1] = Double.parseDouble(values[i+1]);
            region++;
        }
    }

    public String toDataString() {
        final StringBuilder sb = new StringBuilder();
        sb.append(numberOfRegionRows).append(',');
        sb.append(numberOfRegionColumns).append(',');
        sb.append(coefficientsPerRegion);
        for (final double[] regionCoefficients : coefficients) {
            for (final double coefficient : regionCoefficients) {
                sb.append(',').append(coefficient);
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return this.toDataString();
    }

    @Override
    public Map<String, String> toParametersMap() {
        final Map<String, String> map = new HashMap<>();
        map.put(DATA_STRING_NAME, this.toDataString());
        return map;
    }

}

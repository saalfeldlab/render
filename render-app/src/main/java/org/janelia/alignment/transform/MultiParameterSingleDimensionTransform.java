package org.janelia.alignment.transform;

import java.util.regex.Pattern;

import mpicbg.trakem2.transform.CoordinateTransform;

/**
 * Base class for coordinate transforms that transform one dimension using some number of defined coefficients.
 */
public abstract class MultiParameterSingleDimensionTransform
        implements CoordinateTransform {

    protected int dimension;
    protected double[] coefficients;

    public MultiParameterSingleDimensionTransform(final double[] coefficients,
                                                  final int dimension) {
        this.coefficients = coefficients;
        this.dimension = dimension;
    }

    public abstract int getNumberOfCoefficients();

    @Override
    public double[] apply(final double[] location) {
        final double[] out = location.clone();
        applyInPlace(out);
        return out;
    }

    @Override
    public abstract void applyInPlace(final double[] location);

    /**
     * @param  data  string serialization of transform.
     *
     * @throws IllegalArgumentException
     *   if any errors occur during parsing.
     */
    @Override
    public void init(final String data) throws IllegalArgumentException {

        final String[] fields = DELIM_PATTERN.split(data);
        final int numberOfCoefficients = getNumberOfCoefficients();
        final int numberOfValues = numberOfCoefficients + 1;
        if (fields.length != numberOfValues) {
            throw new IllegalArgumentException("transform data must contain " + numberOfValues +
                                               " comma separated values (" + numberOfCoefficients +
                                               " coefficients followed by dimension), found " +
                                               fields.length + " values instead");
        }
        this.coefficients = new double[numberOfCoefficients];
        for (int i = 0; i < numberOfCoefficients; i++) {
            this.coefficients[i] = Double.parseDouble(fields[i]);
        }
        this.dimension = Integer.parseInt(fields[numberOfCoefficients]);
    }

    @Override
    public String toXML(final String indent) {
        return indent + "<ict_transform class=\"" + this.getClass().getCanonicalName() +
               "\" data=\"" + toDataString() + "\"/>";
    }

    @Override
    public String toDataString() {
        return toString();
    }

    @Override
    public abstract CoordinateTransform copy();

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        for (final double coefficient : coefficients) {
            sb.append(coefficient);
            sb.append(',');
        }
        sb.append(dimension);
        return sb.toString();
    }

    private static final Pattern DELIM_PATTERN = Pattern.compile("[, ]+");
}

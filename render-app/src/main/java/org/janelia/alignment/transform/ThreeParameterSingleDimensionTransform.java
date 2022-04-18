package org.janelia.alignment.transform;

import mpicbg.trakem2.transform.CoordinateTransform;

/**
 * Base class for coordinate transforms that transform one dimension using 3 defined coefficients.
 */
public abstract class ThreeParameterSingleDimensionTransform
        implements CoordinateTransform {

    protected double a;
    protected double b;
    protected double c;
    protected int dimension;

    public ThreeParameterSingleDimensionTransform() {
        this(0, 0, 0,0);
    }

    public ThreeParameterSingleDimensionTransform(final double a,
                                                  final double b,
                                                  final double c,
                                                  final int dimension) {
        this.a = a;
        this.b = b;
        this.c = c;
        this.dimension = dimension;
    }

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

        final String[] fields = data.split(",");
        if (fields.length != 4) {
            throw new IllegalArgumentException("transform data must contain 4 comma separated values (a, b, c, and dimension)");
        }
        this.a = Double.parseDouble(fields[0]);
        this.b = Double.parseDouble(fields[1]);
        this.c = Double.parseDouble(fields[2]);
        this.dimension = Integer.parseInt(fields[3]);
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
        return a + "," + b + "," + c + "," + dimension;
    }
}

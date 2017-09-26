package org.janelia.alignment.transform;

import mpicbg.models.AffineModel2D;
import mpicbg.trakem2.transform.CoordinateTransform;

import net.imglib2.RandomAccessible;
import net.imglib2.RealRandomAccess;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.view.composite.RealComposite;

/**
 * Transform that utilizes an {@link AffineWarpField}.
 *
 * @author Eric Trautman
 */
public class AffineWarpFieldTransform
        implements CoordinateTransform {

    private AffineWarpField affineWarpField;

    // ImgLib2 accessor for warp field
    private RealRandomAccess<RealComposite<DoubleType>> warpFieldAccessor;

    /**
     * Default constructor applies identity transform to entire space.
     */
    public AffineWarpFieldTransform() {
        this(new AffineWarpField());
    }

    public AffineWarpFieldTransform(final AffineWarpField affineWarpField)
            throws IllegalArgumentException {

        this.affineWarpField = affineWarpField;
        setWarpFieldAccessor();
    }

    @Override
    public double[] apply(final double[] location) {
        final double[] out = location.clone();
        applyInPlace(out);
        return out;
    }

    @Override
    public void applyInPlace(final double[] location) {
        final AffineModel2D model = getWarpedAffineModel(location);
        model.applyInPlace(location);
    }

    public AffineModel2D getWarpedAffineModel(final double[] location) {

        // location is full scale local (relative) coordinate within current tile,
        // need to offset that location into the encompassing warp field
        final double xOffset = affineWarpField.getX();
        final double yOffset = affineWarpField.getY();
        final double[] offsetLocation = { location[0] + xOffset, location[1] + yOffset };

        warpFieldAccessor.setPosition(offsetLocation);

        // retrieve interpolated affine parameters from warp field
        final RealComposite<DoubleType> coefficients = warpFieldAccessor.get();

        final AffineModel2D model = new AffineModel2D();
        model.set(coefficients.get(0).getRealDouble(), coefficients.get(1).getRealDouble(),
                  coefficients.get(2).getRealDouble(), coefficients.get(3).getRealDouble(),
                  coefficients.get(4).getRealDouble() + xOffset, coefficients.get(5).getRealDouble() + yOffset);

        return model;
    }

    @Override
    public void init(final String data) throws NumberFormatException {
        affineWarpField = deserializeWarpField(data);
        setWarpFieldAccessor();
    }

    @Override
    public String toXML(final String indent) {
        final StringBuilder xml = new StringBuilder();
        xml.append(indent).append("<ict_transform class=\"")
                .append(this.getClass().getCanonicalName())
                .append("\" data=\"");
        serializeWarpField(affineWarpField, xml);
        return xml.append("\"/>").toString();
    }

    @Override
    public String toDataString() {
        final StringBuilder data = new StringBuilder();
        serializeWarpField(affineWarpField, data);
        return data.toString();
    }

    @Override
    public CoordinateTransform copy() {
        return new AffineWarpFieldTransform(affineWarpField.getCopy());
    }

    private void setWarpFieldAccessor() throws IllegalArgumentException {
        // set accessor and validate interpolator factory instance
        try {
            warpFieldAccessor = affineWarpField.getAccessor();
        } catch (final Exception e) {
            final String factoryClassName = affineWarpField.getInterpolatorFactory().getClass().getCanonicalName();
            throw new IllegalArgumentException("interpolator factory class '" + factoryClassName + "' does not implement required interface", e);
        }
    }

    /**
     * Appends serialization of the specified warp field to the specified data string.
     *
     * @param  affineWarpField  field to serialize.
     * @param  data             target data string.
     */
    private static void serializeWarpField(final AffineWarpField affineWarpField,
                                           final StringBuilder data) {
        data.append(affineWarpField.getX()).append(' ').append(affineWarpField.getY()).append(' ');
        data.append(affineWarpField.getWidth()).append(' ').append(affineWarpField.getHeight()).append(' ');
        data.append(affineWarpField.getRowCount()).append(' ').append(affineWarpField.getColumnCount()).append(' ');
        final InterpolatorFactory<RealComposite<DoubleType>, RandomAccessible<RealComposite<DoubleType>>> factory =
                affineWarpField.getInterpolatorFactory();
        data.append(factory.getClass().getCanonicalName()).append(' ');
        final double[] values = affineWarpField.getValues();
        if (values.length < 64) { // skip encoding for smaller fields to simplify visual inspection and testing
            data.append(NO_ENCODING);
            for (final double value : values) {
                data.append(' ').append(value);
            }
        } else {
            data.append(BASE_64_ENCODING).append(' ').append(DoubleArrayConverter.encodeBase64(values));
        }
    }

    /**
     * De-serializes a warp field instance from the specified data string.
     *
     * Note that before using instance, interpolator factory must be validated by
     * calling {@link AffineWarpField#getAccessor}.
     *
     * @param  data  string serialization of a warp field.
     *
     * @return warp field constructed from specified data string.
     *
     * @throws IllegalArgumentException
     *   if any errors occur during parsing.
     */
    private static AffineWarpField deserializeWarpField(final String data) throws IllegalArgumentException {

        final AffineWarpField affineWarpField;

        final String[] fields = data.split("\\s+");

        final int valuesStartIndex = 8;

        if (fields.length > valuesStartIndex) {

            final double x = Double.parseDouble(fields[0]);
            final double y = Double.parseDouble(fields[1]);
            final double width = Double.parseDouble(fields[2]);
            final double height = Double.parseDouble(fields[3]);
            final int rowCount = Integer.parseInt(fields[4]);
            final int columnCount = Integer.parseInt(fields[5]);
            final InterpolatorFactory<RealComposite<DoubleType>, RandomAccessible<RealComposite<DoubleType>>>
                    interpolatorFactory =
                    buildInterpolatorFactoryInstance(fields[6]);
            final String encoding = fields[7];

            final int size = AffineWarpField.getSize(rowCount, columnCount);
            final double[] values;

            if (BASE_64_ENCODING.equals(encoding)) {

                try {
                    values = DoubleArrayConverter.decodeBase64(fields[valuesStartIndex], size);
                } catch (final Exception e) {
                    throw new IllegalArgumentException("failed to decode warp field values", e);
                }

            } else {

                final int expectedSize = size + valuesStartIndex;

                if (fields.length == expectedSize) {

                    values = new double[size];

                    for (int i = valuesStartIndex; i < fields.length; i++) {
                        values[i - valuesStartIndex] = Double.parseDouble(fields[i]);
                    }

                } else {
                    throw new IllegalArgumentException("expected warp field data to contain " + expectedSize +
                                                       " fields but found " + fields.length + " instead");
                }

            }

            affineWarpField = new AffineWarpField(x, y, width, height, rowCount, columnCount, values, interpolatorFactory);

        } else {
            throw new IllegalArgumentException("warp field data must contain at least " + valuesStartIndex + " fields");
        }

        return affineWarpField;
    }

    /**
     * @return a factory instance for the specified class name.
     *         Note that instance may not completely implement interface because type erasure prevents this check.
     *
     * @throws IllegalArgumentException
     *   if an instance cannot be created.
     */
    private static InterpolatorFactory<RealComposite<DoubleType>, RandomAccessible<RealComposite<DoubleType>>> buildInterpolatorFactoryInstance(final String className)
            throws IllegalArgumentException {

        final Class clazz;
        try {
            clazz = Class.forName(className);
        } catch (final ClassNotFoundException e) {
            throw new IllegalArgumentException("class '" + className + "' cannot be found", e);
        }

        final Object instance;
        try {
            instance = clazz.newInstance();
        } catch (final Exception e) {
            throw new IllegalArgumentException("failed to create instance of '" + className + "'", e);
        }

        final InterpolatorFactory<RealComposite<DoubleType>, RandomAccessible<RealComposite<DoubleType>>> factory;
        try {
            //noinspection unchecked
            factory = (InterpolatorFactory<RealComposite<DoubleType>, RandomAccessible<RealComposite<DoubleType>>>) instance;
        } catch (final Exception e) {
            throw new IllegalArgumentException("class '" + className + "' does not implement required interface", e);
        }

        return factory;
    }

    private static final String BASE_64_ENCODING = "base64";
    private static final String NO_ENCODING = "none";

}

package org.janelia.alignment.transform;

import java.io.Serializable;
import java.util.Arrays;

import net.imglib2.RandomAccessible;
import net.imglib2.RealRandomAccess;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.DoubleArray;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.ScaleAndTranslation;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.view.Views;
import net.imglib2.view.composite.CompositeIntervalView;
import net.imglib2.view.composite.RealComposite;


/**
 * A set of affine parameters distributed within a grid stretched across a pixel area.
 *
 * @author Eric Trautman
 */
public class AffineWarpField
        implements Serializable {

    private final double width;
    private final double height;
    private final int rowCount;
    private final int columnCount;

    private final double[] values;

    private final InterpolatorFactory<RealComposite<DoubleType>, RandomAccessible<RealComposite<DoubleType>>> interpolatorFactory;

    /**
     * Constructs a simple 1x1 identify field.
     */
    public AffineWarpField() {
        this(1, 1, 1, 1, getDefaultInterpolatorFactory());
    }

    /**
     * Constructs a field with the specified dimensions.
     * Each affine is initialized with identity values.
     *
     * @param  width                pixel width of the warp field.
     * @param  height               pixel height of the warp field.
     * @param  rowCount             number of affine rows in the warp field.
     * @param  columnCount          number of affine columns in the warp field.
     * @param  interpolatorFactory  factory for desired interpolator instance.
     */
    public AffineWarpField(final double width,
                           final double height,
                           final int rowCount,
                           final int columnCount,
                           final InterpolatorFactory<RealComposite<DoubleType>, RandomAccessible<RealComposite<DoubleType>>> interpolatorFactory)
            throws IllegalArgumentException {
        this(width, height, rowCount, columnCount,
             getDefaultValues(rowCount, columnCount),
             interpolatorFactory);
    }

    /**
     * Constructs a field with the specified dimensions and affine values.
     *
     * <p>
     * Ordering of the affine values array is non-intuitive, so it might be easier to use
     * the 4-parameter constructor in conjunction with the {@link #set} method.
     * If you want to punish yourself, here's an example of the value ordering for a 2-row-by-2-column warp field:
     * </p>
     *
     * <pre>
     *        upper  upper  lower  lower
     *         left  right   left  right
     *         x0y0,  x1y0,  x0y1,  x1y1
     *
     *            1,     1,     1,     1,          m00
     *            0,     0,     0,     0,          m10
     *            0,     0,     0,     0,          m01
     *            1,     1,     1,     1,          m11
     *            0,     0,     0,    29,          m02
     *            0,     0,     0,    29           m12
     * </pre>
     *
     * @param  width                pixel width of the warp field.
     * @param  height               pixel height of the warp field.
     * @param  rowCount             number of affine rows in the warp field.
     * @param  columnCount          number of affine columns in the warp field.
     * @param  values               affine values with row-column-affine ordering (see example above).
     * @param  interpolatorFactory  factory for desired interpolator instance.
     *
     * @throws IllegalArgumentException
     *   if rowCount < 1, columnCount < 1, or there is any inconsistency between
     *   rowCount, columnCount, and the values array length.
     */
    public AffineWarpField(final double width,
                           final double height,
                           final int rowCount,
                           final int columnCount,
                           final double[] values,
                           final InterpolatorFactory<RealComposite<DoubleType>, RandomAccessible<RealComposite<DoubleType>>> interpolatorFactory)
            throws IllegalArgumentException {

        final int size = getSize(rowCount, columnCount);
        if (size == 0) {
            throw new IllegalArgumentException("warp field must have at least 1 row and 1 column");
        } else if (size != values.length) {
            throw new IllegalArgumentException("invalid number of warp field values, expected " +
                                               size + " but was " + values.length);
        }

        this.width = width;
        this.height = height;
        this.rowCount = rowCount;
        this.columnCount = columnCount;
        this.values = values;
        this.interpolatorFactory = interpolatorFactory;
    }

    public double getWidth() {
        return width;
    }

    public double getHeight() {
        return height;
    }

    public int getRowCount() {
        return rowCount;
    }

    public int getColumnCount() {
        return columnCount;
    }

    public double[] getValues() {
        return values;
    }

    public InterpolatorFactory<RealComposite<DoubleType>, RandomAccessible<RealComposite<DoubleType>>> getInterpolatorFactory() {
        return interpolatorFactory;
    }

    /**
     * @return the scale factor to stretch the field's affine values across it's pixel width.
     */
    public double getXScale() {
        return width / columnCount;
    }

    /**
     * @return the scale factor to stretch the field's affine values across it's pixel height.
     */
    public double getYScale() {
        return height / rowCount;
    }

    /**
     * Saves the affine values for a grid cell.
     *
     * @param  row                   row for affine.
     * @param  column                column for affine.
     * @param  affineMatrixElements  affine values in 'java' order: m00, m10, m01, m11, m02, m12
     */
    public void set(final int row,
                    final int column,
                    final double[] affineMatrixElements) {

        //            x0y0,  x1y0,  x0y1,  x1y1
        //               1,     1,     1,     1,     m00
        //               0,     0,     0,     0,     m10
        //               0,     0,     0,     0,     m01
        //               1,     1,     1,     1,     m11
        //               0,     0,     0,    29,     m02
        //               0,     0,     0,    29      m12

        final int affineCount = rowCount * columnCount;
        final int startIndex = (row * columnCount) + column;
        int valuesIndex;
        for (int i = 0; i < VALUES_PER_AFFINE; i++) {
            valuesIndex = startIndex + (i * affineCount);
            values[valuesIndex] = affineMatrixElements[i];
        }
    }

    /**
     * Logic stolen from
     * <a href='https://github.com/trakem2/TrakEM2/blob/master/TrakEM2_/src/main/java/org/janelia/intensity/LinearIntensityMap.java'>
     *   TrakEM2 LinearIntensityMap
     * </a>.
     *
     * @return an accessor for deriving warped pixel intensities.
     */
    public RealRandomAccess<RealComposite<DoubleType>> getAccessor() {

        final ArrayImg<DoubleType, DoubleArray> warpField =
                ArrayImgs.doubles(values, columnCount, rowCount, VALUES_PER_AFFINE);

        final CompositeIntervalView<DoubleType, RealComposite<DoubleType>>
                collapsedSource = Views.collapseReal(warpField);

        final RandomAccessible<RealComposite<DoubleType>> extendedCollapsedSource = Views.extendBorder(collapsedSource);
        final RealRandomAccessible<RealComposite<DoubleType>> coefficients =
                Views.interpolate(extendedCollapsedSource, interpolatorFactory);

        final double xScale = getXScale();
        final double yScale = getYScale();
        final double[] scale = { xScale, yScale };
        final double[] shift = { 0.5 * xScale , 0.5 * yScale };

        final ScaleAndTranslation scaleAndTranslation = new ScaleAndTranslation(scale, shift);

        final RealRandomAccessible<RealComposite<DoubleType>> stretchedCoefficients =
                RealViews.transform(coefficients, scaleAndTranslation);

        return stretchedCoefficients.realRandomAccess();
    }

    /**
     * @return a deep copy of this field that is safe for other uses.
     */
    public AffineWarpField getCopy() {
        final double[] valuesCopy = Arrays.copyOf(values, values.length);
        return new AffineWarpField(width, height, rowCount, columnCount, valuesCopy, interpolatorFactory);
    }

    /**
     * @return the default interpolator factory for warp field instances.
     */
    public static InterpolatorFactory<RealComposite<DoubleType>, RandomAccessible<RealComposite<DoubleType>>> getDefaultInterpolatorFactory() {
        return new NLinearInterpolatorFactory<>();
    }

    public static int getSize(final int rowCount,
                               final int columnCount) {
        return rowCount * columnCount * VALUES_PER_AFFINE;
    }

    private static double[] getDefaultValues(final int rowCount,
                                             final int columnCount) {

        //            x0y0,  x1y0,  x0y1,  x1y1
        //               1,     1,     1,     1,     m00
        //               0,     0,     0,     0,     m10
        //               0,     0,     0,     0,     m01
        //               1,     1,     1,     1,     m11
        //               0,     0,     0,    29,     m02
        //               0,     0,     0,    29      m12

        final int size = getSize(rowCount, columnCount);
        final double[] defaultValues = new double[size];
        final int affineCount = rowCount * columnCount;
        final int scaleYStartIndex = 3 * affineCount;

        // init values to identity transform: 1 0 0 1 0 0
        // only need to worry about the 1's
        for (int i = 0; i < affineCount; i++) {
            defaultValues[i] = 1;
            defaultValues[scaleYStartIndex + i] = 1;
        }

        return defaultValues;
    }

    private static final int VALUES_PER_AFFINE = 6;
}

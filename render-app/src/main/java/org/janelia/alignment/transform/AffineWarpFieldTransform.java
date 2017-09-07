package org.janelia.alignment.transform;

import mpicbg.trakem2.transform.CoordinateTransform;

import net.imglib2.RealRandomAccess;
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
        this.warpFieldAccessor = affineWarpField.getAccessor();
    }

    @Override
    public double[] apply(final double[] location) {
        final double[] out = location.clone();
        applyInPlace(out);
        return out;
    }

    @Override
    public void applyInPlace(final double[] location) {

        warpFieldAccessor.setPosition(location);
        final RealComposite<DoubleType> coefficients = warpFieldAccessor.get();

        // TODO: should we use AffineTransform2D instance here to avoid copying (simple) algorithm or is this okay?
        final double m00 = coefficients.get(0).getRealDouble();
        final double m10 = coefficients.get(1).getRealDouble();
        final double m01 = coefficients.get(2).getRealDouble();
        final double m11 = coefficients.get(3).getRealDouble();
        final double m02 = coefficients.get(4).getRealDouble();
        final double m12 = coefficients.get(5).getRealDouble();

        final double l0 = location[0];
        location[0] = l0 * m00 + location[1] * m01 + m02;
        location[1] = l0 * m10 + location[1] * m11 + m12;
    }

    @Override
    public void init(final String data) throws NumberFormatException {
        affineWarpField = AffineWarpField.fromDataString(data);
        warpFieldAccessor = affineWarpField.getAccessor();
    }

    @Override
    public String toXML(final String indent) {
        final StringBuilder xml = new StringBuilder();
        xml.append(indent).append("<ict_transform class=\"")
                .append(this.getClass().getCanonicalName())
                .append("\" data=\"");
        affineWarpField.toDataString(xml);
        return xml.append("\"/>").toString();
    }

    @Override
    public String toDataString() {
        final StringBuilder data = new StringBuilder();
        affineWarpField.toDataString(data);
        return data.toString();
    }

    @Override
    public CoordinateTransform copy() {
        return new AffineWarpFieldTransform(affineWarpField.getCopy());
    }

}

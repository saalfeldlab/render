package org.janelia.alignment.transform;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import mpicbg.trakem2.transform.CoordinateTransform;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccess;
import net.imglib2.converter.Converters;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineRandomAccessible;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Scale;
import net.imglib2.realtransform.ScaleAndTranslation;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import net.imglib2.view.composite.RealComposite;

import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.N5Factory;
import org.janelia.saalfeldlab.n5.universe.N5Factory.StorageFormat;

/**
 * Transform that reads a dense displacement (translation vector) field from a file on disk and adds the
 * interpolated vector at each queried location to that location.
 * <p>
 * TODO: this is a scaffold. The {@link #loadFieldAccessor} method currently assumes an N5/HDF5-style container
 * openable with {@link N5Utils}; extend it (or the source parsing) to support whatever field formats are needed.
 * </p>
 */
public class DisplacementFieldTransform
        implements CoordinateTransform {

    /** URI (as supplied to {@link #init}) identifying the field on disk and its world-coordinate mapping. */
    private String sofimaField;

    /** The scale index at which the deformed images were fed to SOFIMA, needed for vector size adjustment */
    private int scaleIndexSOFIMAinput;

    /** World coordinate of field sample (0, 0); subtracted from a location before querying the field. */
    private double[] locationOffsets;

    /** World pixels spanned by one field sample along each axis; used to stretch the field over pixel space. */
    private double[] scale;

    // ImgLib2 accessor for the displacement field; null until a field source has been loaded.
    private transient RealRandomAccess<RealComposite<DoubleType>> fieldAccessor;

    /**
     * Reflection constructor; leaves the instance uninitialized until {@link #init(String)} is called.
     */
    public DisplacementFieldTransform() {
        this.sofimaField = null;
        this.scaleIndexSOFIMAinput = 0;
        this.locationOffsets = new double[] {0.0, 0.0};
        this.scale = new double[] {1.0, 1.0};
        this.fieldAccessor = null;
    }

    /**
     * Constructs and immediately loads a transform for the field at the specified source.
     *
     * @param  sofimaField  URI locating the field on disk (see class Javadoc for format).
     * @param  scaleIndexSOFIMAinput  The scale index at which the deformed images were fed to SOFIMA, needed for vector size adjustment
     * @param  locationOffsets world coordinate of field sample (0, 0).
     * @param  scale           world pixels per field sample along each axis.
     *
     * @throws IllegalArgumentException
     *   if the field cannot be loaded.
     */
    public DisplacementFieldTransform(final String sofimaField,
                                      final int scaleIndexSOFIMAinput,
                                      final int sofimaZindex, // which slice?
                                      final int[] fullResSize,
                                      final double[] locationOffsets,
                                      final double[] scale)
            throws IllegalArgumentException {
        this.sofimaField = sofimaField;
        this.locationOffsets = locationOffsets;
        this.scale = scale;
        this.fieldAccessor = loadFieldAccessor();

        // has to go to init()
        final N5Reader sofimaContainer = new N5Factory().openReader( StorageFormat.N5, sofimaField );

		//
		// load the SOFIMA relative deformation field and scale it
		//

		// XY axes are flipped compared to python (N5 solves that already)
		// still, first slice are X vectors, 2nd slice are Y vectors
		final RandomAccessibleInterval< DoubleType > sofimaRaw = N5Utils.open( sofimaContainer, "/" );

		System.out.println( Util.printInterval( sofimaRaw ));
		//System.exit( 0 );

		// Note: the SOFIMA field can contain NaN's, could be in FloatType from the start
		final RandomAccessibleInterval< DoubleType > sofima = Converters.convertRAI(
					(RandomAccessibleInterval< FloatType >)(RandomAccessibleInterval)sofimaRaw, // michal's field is actually float
					(i,o) -> o.set( Double.isNaN( i.getRealDouble() ) ? 0 : i.getRealDouble() ), // maybe interpolate/inpaint?
					new DoubleType() );

		// Michal's field is 4D, [2342, 2374, 2, 91]; the ZARR to N5 conversion mixed up Z and C, now [X,Y,C,Z]
		System.out.println( "dimensions of SOFIMA deformation field: " + Arrays.toString( sofima.dimensionsAsLongArray() ) );

		final Interval fullRes2dInterval = new FinalInterval( fullResSize[ 0 ], fullResSize[ 1 ] );
		final Interval sofima2DInterval = new FinalInterval( sofima.dimension( 0 ), sofima.dimension( 1 ) );

		// we have to now convert this transformation to full resolution
		final double[] scalingFactorSofima = scalingFactor( fullRes2dInterval, sofima2DInterval );

		System.out.println( "scalingFactorSofima: " + Arrays.toString( scalingFactorSofima ) );

		// the vectors are scaled relative to the input image size, i.e. we need to know at which factor the images
		// that were fed into SOFIMA were scaled
		final double sofimaBaseScale = 1.0 / (1 << scaleIndexSOFIMAinput );

		final AffineRandomAccessible<DoubleType, AffineGet> transformedSofimaX, transformedSofimaY;

		// TODO: this is a rough approximation, need to handle this properly (right now x and y factor is slightly different)
		transformedSofimaX = RealViews.affine(
				Views.interpolate(
						Views.extendMirrorDouble( Views.hyperSlice( Views.hyperSlice( sofima, 3, sofimaZindex), 2, 0 ) ),
						new NLinearInterpolatorFactory<>()),
				new Scale( scalingFactorSofima ) );

		// TODO: this is a rough approximation, need to handle this properly (right now x and y factor is slightly different)
		transformedSofimaY = RealViews.affine(
				Views.interpolate(
						Views.extendMirrorDouble( Views.hyperSlice( Views.hyperSlice( sofima, 3, sofimaZindex), 2, 1 ) ),
						new NLinearInterpolatorFactory<>()),
				new Scale( scalingFactorSofima ) );

		// FROM HOT-KNIFE
		//
		// we need to adjust the sofima vectors for the original scale of the images and the scale of the hot-knife field
		//
		// The SOFIMA vectors have the same size, no matter with which stride they were computed,
		// so they must be in the size of the input images fed to SOFIMA
		//
		// Saalfeld's absolute transformation fields store the vectors in the scale the transformation fields
		// are stored in. E.g. at scale 0.03125 a value that is 2400, will be 4800 at scale 0.0625
		//
		// Next topic, values:
		// SOFIMA imports e.g. 343, 516 X=1.3092;Y=7.3169 (positive means move up)
		// SOFIMA x positive means move left

		// needs to be returned/exposed
		RandomAccessible<DoubleType> fullResX = Converters.convert(
				(RandomAccessible<DoubleType>)transformedSofimaX,
				(i,o) -> o.set( i.get() / sofimaBaseScale ), // maybe needs sign flip, maybe need to switch X and Y
				new DoubleType() );

		// needs to be returned/exposed
		RandomAccessible<DoubleType> fullResY = Converters.convert(
				(RandomAccessible<DoubleType>)transformedSofimaY,
				(i,o) -> o.set( i.get() / sofimaBaseScale ), // maybe needs sign flip, maybe need to switch X and Y
				new DoubleType() );

    }

	public static double[] scalingFactor( final Interval a, Interval b )
	{
		final double[] s = new double[ a.numDimensions() ];

		for ( int d = 0; d < a.numDimensions(); ++d )
			s[ d ] = (double) a.dimension( d ) / (double) b.dimension( d );

		return s;
	}

    @Override
    public double[] apply(final double[] location) {
        final double[] out = location.clone();
        applyInPlace(out);
        return out;
    }

    @Override
    public void applyInPlace(final double[] location) {

        if (fieldAccessor == null) {
            throw new IllegalStateException(
                    "displacement field has not been loaded; call init(String) before applying this transform");
        }

        final double[] fieldLocation = {
                location[0] - locationOffsets[0],
                location[1] - locationOffsets[1]
        };

        fieldAccessor.setPosition(fieldLocation);
        final RealComposite<DoubleType> displacement = fieldAccessor.get();

        location[0] += displacement.get(0).getRealDouble();
        location[1] += displacement.get(1).getRealDouble();
    }

    /**
     * Initializes this transform by parsing the field source URI and loading the field into an imglib2 image.
     *
     * @param  data  field source URI (see class Javadoc for format).
     *
     * @throws IllegalArgumentException
     *   if the data string cannot be parsed or the field cannot be loaded.
     */
    @Override
    public void init(final String data) throws IllegalArgumentException {
        this.fieldSourceUri = data.trim();
        this.fieldAccessor = loadFieldAccessor();
    }

    @Override
    public String toXML(final String indent) {
        return indent + "<ict_transform class=\"" + this.getClass().getCanonicalName() +
               "\" data=\"" + toDataString() + "\"/>";
    }

    @Override
    public String toDataString() {
        // The source URI already encodes the offsets and scale, so it round-trips through init unchanged.
        return fieldSourceUri;
    }

    @Override
    public CoordinateTransform copy() {
        // Re-loads the field so the copy has an independent accessor (imglib2 accessors are not thread-safe).
        return new DisplacementFieldTransform(fieldSourceUri,
                                              locationOffsets.clone(),
                                              scale.clone());
    }

    @Override
    public String toString() {
        return "{ \"fieldSourceUri\": \"" + fieldSourceUri +
               "\", \"locationOffsets\": [" + locationOffsets[0] + ", " + locationOffsets[1] +
               "], \"scale\": [" + scale[0] + ", " + scale[1] + "] }";
    }

    /**
     * Opens the configured field source into an imglib2 image and builds an interpolating accessor over it.
     *
     * <p>
     * The field is collapsed along its component axis, extended with a border, interpolated, and stretched over
     * pixel space using {@link #scale} and {@link #locationOffsets} (the same pattern as
     * {@link AffineWarpField#getAccessor}). The returned accessor is queried in field-local coordinates (i.e.
     * after {@link #locationOffsets} have been subtracted from the world location).
     * </p>
     *
     * @return an accessor yielding the interpolated {@code (dx, dy)} vector at a field-local location.
     *
     * @throws IllegalArgumentException
     *   if the field cannot be opened or has an unexpected shape.
     */
    private RealRandomAccess<RealComposite<DoubleType>> loadFieldAccessor()
            throws IllegalArgumentException {

        // TODO: this is just a mock-up; fill with actual logic
        if (fieldSourceUri == null) {
            throw new IllegalArgumentException("no field source URI defined");
        }

        this.locationOffsets = new double[] {0.0, 0.0};
        this.scale = new double[] {1.0, 1.0};

        final RandomAccessibleInterval<DoubleType> field = openField(fieldSourceUri);

        final int lastDimension = field.numDimensions() - 1;
        if ((lastDimension < 2) || (field.dimension(lastDimension) != 2)) {
            throw new IllegalArgumentException(
                    "displacement field must have a trailing component axis of length " + 2 +
                    " (dx, dy), but loaded field from '" + fieldSourceUri + "' has shape with last-axis length " +
                    field.dimension(lastDimension));
        }

        // Stretch the field grid across pixel space; shift by half a sample so samples sit at cell centers.
        final double[] shift = { 0.5 * scale[0], 0.5 * scale[1] };
        final ScaleAndTranslation scaleAndTranslation = new ScaleAndTranslation(scale, shift);

        return RealViews.transform(
                Views.interpolate(
                        Views.extendBorder(Views.collapseReal(field)),
                        getInterpolatorFactory()
                ),
                scaleAndTranslation
        ).realRandomAccess();
    }

    /**
     * Opens the field at the specified source into an imglib2 image.
     */
    private static RandomAccessibleInterval<DoubleType> openField(final String sourceUri)
            throws IllegalArgumentException {

        // TODO: Replace or extend this with the loading logic appropriate for the field format(s) actually in use.
        final URI uri;
        try {
            uri = new URI(sourceUri);
        } catch (final URISyntaxException e) {
            throw new IllegalArgumentException("invalid field source URI '" + sourceUri + "'", e);
        }

        final String scheme = uri.getScheme();
        if ((scheme != null) && (! scheme.equals("file"))) {
            // n5universe is not yet a dependency of render-app!
            throw new IllegalArgumentException(scheme + " scheme not currently supported, must be a local file");
        }

        final String basePath = URLDecoder.decode(uri.getPath(), StandardCharsets.UTF_8);
        final String dataset = "/";
        try (final N5Reader n5Reader = new N5FSReader(basePath)) {
            return N5Utils.open(n5Reader, dataset);
        } catch (final Exception e) {
            throw new IllegalArgumentException(
                    "failed to open displacement field dataSet '" + dataset + "' in '" + basePath + "'", e);
        }
    }

    private static InterpolatorFactory<RealComposite<DoubleType>, RandomAccessible<RealComposite<DoubleType>>> getInterpolatorFactory() {
        return new NLinearInterpolatorFactory<>();
    }
}

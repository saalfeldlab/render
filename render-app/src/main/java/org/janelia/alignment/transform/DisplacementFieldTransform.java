package org.janelia.alignment.transform;

import mpicbg.trakem2.transform.CoordinateTransform;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccess;
import net.imglib2.RealRandomAccessible;
import net.imglib2.converter.Converters;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Scale;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.N5Factory;
import org.janelia.saalfeldlab.n5.universe.N5Factory.StorageFormat;

import java.util.HashMap;
import java.util.Map;


/**
 * Transform that reads a dense displacement (translation vector) field from a file on disk and adds the
 * interpolated vector at each queried location to that location.
 */
public class DisplacementFieldTransform
        implements CoordinateTransform {

    /** URI (as supplied to {@link #init}) identifying the field on disk and its world-coordinate mapping. */
    private String fieldSourceUri;
	private double[] xyScale;
	private int fieldScaleIndex;
	private int fieldZIndex;

    // ImgLib2 accessor for the displacement field; null until a field source has been loaded.
    private RealRandomAccess<FloatType> displacementX;
	private RealRandomAccess<FloatType> displacementY;

    /**
     * Reflection constructor; leaves the instance uninitialized until {@link #init(String)} is called.
     */
    public DisplacementFieldTransform() {
        this.fieldSourceUri = null;
		this.xyScale = null;
        this.fieldScaleIndex = -1;
		this.fieldZIndex = -1;

        this.displacementX = null;
		this.displacementY = null;
    }

    /**
     * Constructs and immediately loads a transform for the field at the specified source.
     *
     * @param  fieldSourceUri  URI locating the field on disk (see class Javadoc for format).
     * @param  fieldScaleIndex  The scale index at which the deformed images were fed to SOFIMA, needed for vector size adjustment
	 * @param  fieldZIndex  The z-slice index of the field to use (the field may be 3D, but this transform is 2D)
	 * @param  xyScale  The scale of the field in x and y, needed for scaling the field to full resolution
     *
     * @throws IllegalArgumentException
     *   if the field cannot be loaded.
     */
    public DisplacementFieldTransform(final String fieldSourceUri,
                                      final int fieldScaleIndex,
                                      final int fieldZIndex,
                                      final double[] xyScale) {
		this.init(fieldSourceUri, xyScale, fieldScaleIndex, fieldZIndex);
    }

	private void init(final String fieldSourceUri,
	                  final double[] xyScale,
					  final int fieldScaleIndex,
					  final int fieldZIndex) {
		this.fieldSourceUri = fieldSourceUri;
		this.xyScale = xyScale;
		this.fieldScaleIndex = fieldScaleIndex;
		this.fieldZIndex = fieldZIndex;

		/* Load displacement field. Currently, this is tailored to output from SOFIMA for multi-sem acquisitions.
		 * - Layout is [X,Y,C,Z]; C=0 is X vectors, C=1 is Y vectors
		 * - XY axes are flipped compared to python due to N5 convention
		 */
		final N5Reader fieldReader = new N5Factory().openReader(StorageFormat.N5, fieldSourceUri);
		final RandomAccessibleInterval<FloatType> fieldRaw = N5Utils.open(fieldReader, "/");

		displacementX = extractAndTransform(fieldRaw, 0);
		displacementY = extractAndTransform(fieldRaw, 1);
	}

	/**
	 * Currently, this is tailored to output from SOFIMA for multi-sem acquisitions. The following code and comments
	 * are from hot-knife.
	 * - We need to adjust the sofima vectors for the original scale of the images and the scale of the hot-knife field
	 * - The SOFIMA vectors have the same size, no matter with which stride they were computed, so they must be in the size of the input images fed to SOFIMA
	 * - Saalfeld's absolute transformation fields store the vectors in the scale the transformation fields are stored in. E.g. at scale 0.03125 a value that is 2400, will be 4800 at scale 0.0625
	 * - Positive y means move up, positive x means move left
	 */
	private RealRandomAccess<FloatType> extractAndTransform(final RandomAccessibleInterval<FloatType> rawField,
	                                                        final int xory) {
		// The deformation field can contain NaNs, replace them with zeros
		// Do this up front to not interpolate NaNs
		final RandomAccessibleInterval<FloatType> cleaned = Converters.convertRAI(
				rawField,
				(i, o) -> o.set(Float.isNaN(i.getRealFloat()) ? 0 : i.getRealFloat()),
				new FloatType());

		// Slice the dataset: choose right z-slice (dim=3) and then choose between x or y (dim=2)
		final RandomAccessibleInterval<FloatType> slice = Views.hyperSlice(
				Views.hyperSlice(cleaned, 3, this.fieldZIndex), 2, xory);

		// Scale and interpolate the slice to full resolution
		final RealRandomAccessible<FloatType> scaledAndInterpolated = RealViews.affine(
				Views.interpolate(Views.extendMirrorDouble(slice), new NLinearInterpolatorFactory<>()),
				new Scale(this.xyScale));

		// Scale the deformation vectors to account for the scale of the images that they were computed on
		// Do this last to reduce the number of scaling operations
		final float vectorScale = 1.0f / (1 << this.fieldScaleIndex);
		return Converters.convert(
				scaledAndInterpolated,
				(i, o) -> o.set(i.getRealFloat() / vectorScale),
				new FloatType()).realRandomAccess();
	}

    @Override
    public double[] apply(final double[] location) {
        final double[] out = location.clone();
        applyInPlace(out);
        return out;
    }

    @Override
    public void applyInPlace(final double[] location) {

        if (displacementX == null || displacementY == null) {
            throw new IllegalStateException(
                    "displacement field has not been loaded; call init(String) before applying this transform");
        }

        // Query both components at the original (undisplaced) location before mutating it.
        final double dx = displacementX.setPositionAndGet(location).getRealDouble();
        final double dy = displacementY.setPositionAndGet(location).getRealDouble();
        location[0] += dx;
        location[1] += dy;
    }

    /**
     * Initializes this transform by parsing the data string and loading the field into an imglib2 image.
     * <p>
     * The data string is the field source URI followed by {@code ?key=value} query parameters, e.g.
     * {@code file:///path/to/field.n5?scaleIndex=3&zIndex=5&scaleX=8.0&scaleY=8.0}. The portion before the
     * {@code ?} becomes the {@link #fieldSourceUri} (the actual path); the query parameters supply the
     * remaining fields.
     *
     * @param  data  field source URI with query parameters (see above).
     *
     * @throws IllegalArgumentException
     *   if the data string cannot be parsed or the field cannot be loaded.
     */
    @Override
    public void init(final String data) throws IllegalArgumentException {

        final String trimmed = data.trim();
        final int queryStart = trimmed.indexOf('?');
        if (queryStart < 0) {
            throw new IllegalArgumentException(
                    "transform data must be a field source URI followed by " +
                    "'?scaleIndex=<int>&zIndex=<int>&scaleX=<double>&scaleY=<double>', but was '" + data + "'");
        }

        final String parsedSourceUri = trimmed.substring(0, queryStart);
        final Map<String, String> params = parseQueryParameters(trimmed.substring(queryStart + 1), data);

        init(parsedSourceUri,
             new double[] { parseDoubleParameter(params, "scaleX", data),
                            parseDoubleParameter(params, "scaleY", data) },
             parseIntParameter(params, "scaleIndex", data),
             parseIntParameter(params, "zIndex", data));
    }

    @Override
    public String toXML(final String indent) {
        return indent + "<ict_transform class=\"" + this.getClass().getCanonicalName() +
               "\" data=\"" + toDataString() + "\"/>";
    }

    @Override
    public String toDataString() {
        // Serializes all fields as a source URI plus query parameters so the string round-trips through init.
        return fieldSourceUri +
               "?scaleIndex=" + fieldScaleIndex +
               "&zIndex=" + fieldZIndex +
               "&scaleX=" + xyScale[0] +
               "&scaleY=" + xyScale[1];
    }

    @Override
    public CoordinateTransform copy() {
        // Re-loads the field so the copy has independent accessors (imglib2 accessors are not thread-safe).
        return new DisplacementFieldTransform(fieldSourceUri, fieldScaleIndex, fieldZIndex, xyScale.clone());
    }

    @Override
    public String toString() {
        return "{ \"fieldSourceUri\": \"" + fieldSourceUri +
               "\", \"fieldScaleIndex\": " + fieldScaleIndex +
               ", \"fieldZIndex\": " + fieldZIndex +
               ", \"xyScale\": [" + xyScale[0] + ", " + xyScale[1] + "] }";
    }

    private static Map<String, String> parseQueryParameters(final String query, final String data) {
        final Map<String, String> params = new HashMap<>();
        for (final String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            final int eq = pair.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException(
                        "invalid query parameter '" + pair + "' in transform data '" + data + "'");
            }
            params.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return params;
    }

    private static int parseIntParameter(final Map<String, String> params, final String key, final String data) {
        final String value = requireParameter(params, key, data);
        try {
            return Integer.parseInt(value);
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException(
                    "invalid integer value '" + value + "' for parameter '" + key +
                    "' in transform data '" + data + "'", e);
        }
    }

    private static double parseDoubleParameter(final Map<String, String> params, final String key, final String data) {
        final String value = requireParameter(params, key, data);
        try {
            return Double.parseDouble(value);
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException(
                    "invalid double value '" + value + "' for parameter '" + key +
                    "' in transform data '" + data + "'", e);
        }
    }

    private static String requireParameter(final Map<String, String> params, final String key, final String data) {
        final String value = params.get(key);
        if (value == null) {
            throw new IllegalArgumentException(
                    "missing required parameter '" + key + "' in transform data '" + data + "'");
        }
        return value;
    }
}

package org.janelia.alignment.transform;

import mpicbg.trakem2.transform.CoordinateTransform;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccess;
import net.imglib2.RealRandomAccessible;
import net.imglib2.converter.Converters;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.n5.KeyValueAccess;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.googlecloud.GoogleCloudStorageKeyValueAccess;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.precomputed.N5PrecomputedReader;
import org.janelia.saalfeldlab.n5.precomputed.PrecomputedKeyValueReader;

import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.gson.GsonBuilder;
import org.janelia.saalfeldlab.n5.universe.N5Factory;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Transform that reads a dense displacement (translation vector) field from a file on disk and adds the
 * interpolated vector at each queried location to that location.
 */
public class DisplacementFieldTransform
        implements CoordinateTransform {

    /** URI (as supplied to {@link #init}) identifying the field on disk and its world-coordinate mapping. */
    private String fieldSourceUri;
	private int fieldZIndex;
	/** Full-resolution pixels per field pixel in x and y. */
	private double[] xyScale;
	/** World coordinate that field index 0 maps to, in x and y. */
	private double[] offset;
	/** Full-resolution pixels per unit of stored vector. */
	private double vectorScale;

    // ImgLib2 accessor for the displacement field; null until a field source has been loaded.
    private RealRandomAccess<FloatType> displacementX;
	private RealRandomAccess<FloatType> displacementY;

    /**
     * Reflection constructor; leaves the instance uninitialized until {@link #init(String)} is called.
     */
    public DisplacementFieldTransform() {
        this.fieldSourceUri = null;
		this.fieldZIndex = -1;
		this.xyScale = new double[] { DEFAULT_SCALE, DEFAULT_SCALE };
		this.offset = new double[] { DEFAULT_OFFSET, DEFAULT_OFFSET };
		this.vectorScale = DEFAULT_VECTOR_SCALE;

        this.displacementX = null;
		this.displacementY = null;
    }

    /**
     * Constructs and immediately loads a transform for the field at the specified source.
     *
     * @param  fieldSourceUri  URI locating the field on disk (see class Javadoc for format).
	 * @param  fieldZIndex  The z-slice index of the field to use (the field may be 3D, but this transform is 2D)
	 * @param  xyScale  Full-resolution pixels per field pixel in x and y (1 leaves the field at full resolution)
	 * @param  offset  World coordinate that field index 0 maps to, in x and y (0 puts the field at the world origin)
	 * @param  vectorScale  Full-resolution pixels per unit of stored vector (1 for vectors already in
	 *                      full-resolution units, which is what SOFIMA emits)
     *
     * @throws IllegalArgumentException
     *   if the field cannot be loaded.
     */
    public DisplacementFieldTransform(final String fieldSourceUri,
                                      final int fieldZIndex,
                                      final double[] xyScale,
                                      final double[] offset,
                                      final double vectorScale) {
		this.init(fieldSourceUri, fieldZIndex, xyScale, offset, vectorScale);
    }

	private void init(final String fieldSourceUri,
					  final int fieldZIndex,
	                  final double[] xyScale,
	                  final double[] offset,
					  final double vectorScale) {
		this.fieldSourceUri = fieldSourceUri;
		this.fieldZIndex = fieldZIndex;
		this.xyScale = xyScale;
		this.offset = offset;
		this.vectorScale = vectorScale;

		/* Load displacement field. Currently, this is tailored to output from SOFIMA for multi-sem acquisitions,
		 * stored as a Neuroglancer precomputed volume and read through the n5-ng-precomputed backend.
		 * - Layout is [x,y,z,channel]; channel=0 is X vectors, channel=1 is Y vectors
		 * - Precomputed raw is column-major [x,y,z,channel], matching N5/ImgLib2, so (unlike the Zarr
		 *   backend) no XY axis reversal is performed: dim 0 is X, dim 1 is Y
		 */
		final RandomAccessibleInterval<FloatType> fieldRaw = openRawField(fieldSourceUri);

		// Out-of-range x and y are handled by the mirrored extension in extractAndTransform, but an out-of-range
		// z would read outside the cached image, which is undefined rather than merely inaccurate.
		if ((fieldZIndex < 0) || (fieldZIndex >= fieldRaw.dimension(2))) {
			throw new IllegalArgumentException(
					"zIndex " + fieldZIndex + " is outside the z range [0, " + fieldRaw.dimension(2) +
					") of the field at " + fieldSourceUri);
		}

		displacementX = extractAndTransform(fieldRaw, 0);
		displacementY = extractAndTransform(fieldRaw, 1);
	}

	/**
	 * Cache of raw (scale- and z-independent) displacement fields keyed by source URI. A single tile spec resolves
	 * its transform once per {@code getTransformList()} call (with no per-spec instance caching), so importing or
	 * rendering a layer would otherwise re-open the reader and re-read chunks once per tile. The cached value is the
	 * lazy {@link N5Utils#open} {@code CachedCellImg}: reader open + chunk reads happen once per field and are then
	 * shared across every tile and z-slice. Per-instance accessors are still built fresh in
	 * {@link #extractAndTransform} (imglib2 accessors are not thread safe); only the underlying chunk cache is shared.
	 */
	private static final Map<String, RandomAccessibleInterval<FloatType>> RAW_FIELD_CACHE = new ConcurrentHashMap<>();

	private static RandomAccessibleInterval<FloatType> openRawField(final String fieldSourceUri) {
		return RAW_FIELD_CACHE.computeIfAbsent(fieldSourceUri, uri -> {
			final N5Reader fieldReader = openPrecomputedReader(uri);
			final String scaleKey = fieldReader.list("/")[0];
			return N5Utils.open(fieldReader, scaleKey);
		});
	}

	/**
	 * Opens a Neuroglancer precomputed field through the N5 API. The URI may be prefixed with
	 * {@code precomputed://}. {@code gs://} buckets are read anonymously (matching the public warp-field
	 * bucket); any other scheme (e.g. {@code file://}) is routed through {@link N5Factory}'s key-value access.
	 *
	 * <p>This wires the reader up by hand because {@code n5-universe}'s {@code N5Factory} does not yet know
	 * the precomputed format. Mirrors the {@code n5-ng-precomputed} examples.
	 *
	 * <p>Exposed so that clients preparing a field (e.g. {@code ImportSofimaClient}) open it through exactly
	 * this same path rather than reimplementing the wiring. The field dataset itself lives under the first
	 * scale key, i.e. {@code reader.list("/")[0]}.
	 *
	 * @param  fieldSourceUri  the (optionally {@code precomputed://}-prefixed) container URI.
	 * @return an {@link N5Reader} over the precomputed container.
	 */
	public static N5Reader openPrecomputedReader(final String fieldSourceUri) {
		String uri = fieldSourceUri;
		if (uri.startsWith("precomputed://")) {
			uri = uri.substring("precomputed://".length());
		}

		if (uri.startsWith("gs://")) {
			// gs:// buckets are read anonymously (the public warp-field bucket needs no credentials).
			final Storage storage = StorageOptions.getUnauthenticatedInstance().getService();
			final KeyValueAccess keyValueAccess = new GoogleCloudStorageKeyValueAccess(storage, uri, false);
			return new PrecomputedKeyValueReader(keyValueAccess, uri, new GsonBuilder(), true);
		}

		// Local filesystem (optionally file://-prefixed): N5PrecomputedReader wires up FileSystemKeyValueAccess
		// over the default filesystem. (n5-universe 1.6.0's N5Factory.getKeyValueAccess is package-private.)
		final String path = uri.startsWith("file://") ? URI.create(uri).getPath() : uri;
		return new N5PrecomputedReader(path, new GsonBuilder(), true);
	}

	/**
	 * Currently, this is tailored to output from SOFIMA for multi-sem acquisitions.
	 * <ul>
	 *   <li>The field is a <b>pull</b> map: the vector stored at a target position points at the source position
	 *       the data is pulled from, i.e. {@code source = target + vector}. Render's transform lists run
	 *       source to target, so the vectors are negated here.</li>
	 *   <li>Stored vectors are multiplied by {@code vectorScale} to reach full resolution. SOFIMA expresses them
	 *       in the units of the original volume already, so the default of 1 is what that output needs.</li>
	 * </ul>
	 */
	private RealRandomAccess<FloatType> extractAndTransform(final RandomAccessibleInterval<FloatType> rawField,
	                                                        final int xory) {
		// The deformation field can contain NaNs, replace them with zeros
		// Do this up front to not interpolate NaNs
		final RandomAccessibleInterval<FloatType> cleaned = Converters.convertRAI(
				rawField,
				(i, o) -> o.set(Float.isNaN(i.getRealFloat()) ? 0 : i.getRealFloat()),
				new FloatType());

		// Slice the [x,y,z,channel] dataset: choose the vector component (channel, dim=3) and then the
		// z-slice (dim=2). Slicing the higher dimension (channel) first keeps the z index valid at dim=2.
		final RandomAccessibleInterval<FloatType> slice = Views.hyperSlice(
				Views.hyperSlice(cleaned, 3, xory), 2, this.fieldZIndex);

		// Place the slice in world coordinates: field index 0 lands on offset, one field pixel spans xyScale
		// full-resolution pixels, so a query at p reads the field at (p - offset) / xyScale.
		final AffineTransform2D fieldToWorld = new AffineTransform2D();
		fieldToWorld.set(this.xyScale[0], 0, this.offset[0],
						 0, this.xyScale[1], this.offset[1]);
		final RealRandomAccessible<FloatType> scaledAndInterpolated = RealViews.affine(
				Views.interpolate(Views.extendMirrorDouble(slice), new NLinearInterpolatorFactory<>()),
				fieldToWorld);

		// Invert the pull map and scale the vectors to full resolution, folded into a single factor applied last
		// (after interpolation) to keep the number of passes down.
		// ponytail: negating is a first-order inverse, p - d(p) instead of solving t = p - d(t). Exact enough for
		// the fields seen so far (Jacobian ~2e-4, so it is off by well under 0.01 px); switch to a fixed-point
		// iteration if a field with steep gradients ever shows up.
		final double pullToPushScale = -this.vectorScale;
		return Converters.convert(
				scaledAndInterpolated,
				(i, o) -> o.set((float) (i.getRealFloat() * pullToPushScale)),
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
     * {@code file:///path/to/field.n5?zIndex=5&scaleX=40.0&scaleY=40.0}. The portion before the {@code ?}
     * becomes the {@link #fieldSourceUri} (the actual path); the query parameters supply the remaining fields.
     * Only {@code zIndex} is required; everything else defaults to the identity placement
     * ({@code scaleX=scaleY=vectorScale=1}, {@code offsetX=offsetY=0}). Unknown parameters are rejected so
     * that a misspelled one cannot silently fall back to its default.
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
                    "transform data must be a field source URI followed by '?zIndex=<int>' and optionally " +
                    "'&scaleX=<double>&scaleY=<double>&offsetX=<double>&offsetY=<double>&vectorScale=<double>', " +
                    "but was '" + data + "'");
        }

        final String parsedSourceUri = trimmed.substring(0, queryStart);
        final Map<String, String> params = parseQueryParameters(trimmed.substring(queryStart + 1), data);

        init(parsedSourceUri,
             parseIntParameter(params, "zIndex", data),
             new double[] { parseDoubleParameter(params, "scaleX", DEFAULT_SCALE, data),
                            parseDoubleParameter(params, "scaleY", DEFAULT_SCALE, data) },
             new double[] { parseDoubleParameter(params, "offsetX", DEFAULT_OFFSET, data),
                            parseDoubleParameter(params, "offsetY", DEFAULT_OFFSET, data) },
             parseDoubleParameter(params, "vectorScale", DEFAULT_VECTOR_SCALE, data));
    }

    @Override
    public String toXML(final String indent) {
        return indent + "<ict_transform class=\"" + this.getClass().getCanonicalName() +
               "\" data=\"" + toDataString() + "\"/>";
    }

    @Override
    public String toDataString() {
        // Writes every parameter, including any left at its default, so a persisted string keeps its meaning
        // even if a default ever changes.  Callers building a string by hand may omit the defaulted ones.
        return fieldSourceUri +
               "?zIndex=" + fieldZIndex +
               "&scaleX=" + xyScale[0] +
               "&scaleY=" + xyScale[1] +
               "&offsetX=" + offset[0] +
               "&offsetY=" + offset[1] +
               "&vectorScale=" + vectorScale;
    }

    @Override
    public CoordinateTransform copy() {
        // Re-loads the field so the copy has independent accessors (imglib2 accessors are not thread-safe).
        return new DisplacementFieldTransform(fieldSourceUri, fieldZIndex, xyScale.clone(), offset.clone(), vectorScale);
    }

    @Override
    public String toString() {
        return "{ \"fieldSourceUri\": \"" + fieldSourceUri +
               "\", \"fieldZIndex\": " + fieldZIndex +
               ", \"xyScale\": [" + xyScale[0] + ", " + xyScale[1] + "]" +
               ", \"offset\": [" + offset[0] + ", " + offset[1] + "]" +
               ", \"vectorScale\": " + vectorScale + " }";
    }

    private static final double DEFAULT_SCALE = 1.0;
    private static final double DEFAULT_OFFSET = 0.0;
    private static final double DEFAULT_VECTOR_SCALE = 1.0;

    private static final Set<String> VALID_PARAMETERS =
            Set.of("zIndex", "scaleX", "scaleY", "offsetX", "offsetY", "vectorScale");

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
            final String key = pair.substring(0, eq);
            if (! VALID_PARAMETERS.contains(key)) {
                // Everything but zIndex is optional, so a typo would otherwise silently use the default.
                throw new IllegalArgumentException(
                        "unknown query parameter '" + key + "' in transform data '" + data +
                        "'; supported parameters are " + VALID_PARAMETERS);
            }
            params.put(key, pair.substring(eq + 1));
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

    private static double parseDoubleParameter(final Map<String, String> params,
                                               final String key,
                                               final double defaultValue,
                                               final String data) {
        final String value = params.get(key);
        if (value == null) {
            return defaultValue;
        }
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

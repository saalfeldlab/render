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
import org.janelia.n5.precomputed.N5PrecomputedReader;
import org.janelia.n5.precomputed.PrecomputedKeyValueReader;

import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.gson.GsonBuilder;
import org.janelia.alignment.util.QueryKeyValueParameters;
import org.janelia.saalfeldlab.n5.universe.N5Factory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Moves each queried location by a displacement vector interpolated from a field on disk. The field is a pull
 * map (see {@link #extractAndTransform}), so applying it means inverting the field, done in {@link #applyInPlace}
 * by solving the bilinearly interpolated field exactly, cell by cell.
 */
public class DisplacementFieldTransform
        implements CoordinateTransform {

    /** URI (as supplied to {@link #init}) identifying the field on disk and its world-coordinate mapping. */
    private String fieldSourceUri;
	private int fieldZIndex;
	/** Full-resolution pixels per field pixel; the same in x and y. */
	private double scale;
	/** World coordinate that field index 0 maps to, in x and y. */
	private double[] offset;
	/** Full-resolution pixels per unit of stored vector. */
	private double vectorScale;

    // ImgLib2 accessor for the displacement field; null until a field source has been loaded.
    private RealRandomAccess<FloatType> displacementX;
	private RealRandomAccess<FloatType> displacementY;

	/** Whether this instance has already logged a non-converging inversion (see {@link #applyInPlace}). */
	private boolean divergenceLogged = false;

    /**
     * Reflection constructor; leaves the instance uninitialized until {@link #init(String)} is called.
     */
    public DisplacementFieldTransform() {
        this.fieldSourceUri = null;
		this.fieldZIndex = -1;
		this.scale = DEFAULT_SCALE;
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
	 * @param  scale  Full-resolution pixels per field pixel, in x and y alike (1 leaves the field at full resolution)
	 * @param  offset  World coordinate that field index 0 maps to, in x and y (0 puts the field at the world origin)
	 * @param  vectorScale  Full-resolution pixels per unit of stored vector (1 for vectors already in
	 *                      full-resolution units, which is what SOFIMA emits)
     *
     * @throws IllegalArgumentException
     *   if the field cannot be loaded.
     */
    public DisplacementFieldTransform(final String fieldSourceUri,
                                      final int fieldZIndex,
                                      final double scale,
                                      final double[] offset,
                                      final double vectorScale) {
		this.init(fieldSourceUri, fieldZIndex, scale, offset, vectorScale);
    }

	private void init(final String fieldSourceUri,
					  final int fieldZIndex,
	                  final double scale,
	                  final double[] offset,
					  final double vectorScale) {
		this.fieldSourceUri = fieldSourceUri;
		this.fieldZIndex = fieldZIndex;
		this.scale = scale;
		this.offset = offset;
		this.vectorScale = vectorScale;

		// SOFIMA output as a Neuroglancer precomputed volume, layout [x,y,z,channel], channel 0/1 = X/Y vectors.
		final RandomAccessibleInterval<FloatType> fieldRaw = openRawField(fieldSourceUri);

		// x/y out of range is handled by the mirrored extension in extractAndTransform; z is not, so check it here.
		if ((fieldZIndex < 0) || (fieldZIndex >= fieldRaw.dimension(2))) {
			throw new IllegalArgumentException(
					"z " + fieldZIndex + " is outside the z range [0, " + fieldRaw.dimension(2) +
					") of the field at " + fieldSourceUri);
		}

		displacementX = extractAndTransform(fieldRaw, 0);
		displacementY = extractAndTransform(fieldRaw, 1);
	}

	/**
	 * Cache of raw fields keyed by source URI, so a layer's tiles share one reader and chunk cache instead of each
	 * re-opening it. Per-instance accessors are still built fresh in {@link #extractAndTransform} since imglib2
	 * accessors aren't thread-safe.
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
	 * Opens a Neuroglancer precomputed field (optionally {@code precomputed://}-prefixed) through the N5 API.
	 * Wired up by hand since {@code n5-universe}'s {@code N5Factory} doesn't know the precomputed format yet.
	 * {@code gs://} buckets are read anonymously; other schemes go through {@link N5Factory}'s key-value access.
	 * Exposed so field-preparing clients (e.g. {@code ImportSofimaClient}) use the same path. The dataset lives
	 * under the first scale key, {@code reader.list("/")[0]}.
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
	 * The field is a <b>pull</b> map: the vector at a target position points at the source it was pulled from,
	 * i.e. {@code source = target + vector}. Render's transform lists run source to target, so the vectors are
	 * negated here and scaled by {@code vectorScale} to full resolution.
	 */
	private RealRandomAccess<FloatType> extractAndTransform(final RandomAccessibleInterval<FloatType> rawField,
	                                                        final int xory) {
		// Replace NaNs before interpolating, so they don't leak into neighboring pixels.
		final RandomAccessibleInterval<FloatType> cleaned = Converters.convertRAI(
				rawField,
				(i, o) -> o.set(Float.isNaN(i.getRealFloat()) ? 0 : i.getRealFloat()),
				new FloatType());

		// Slice the [x,y,z,channel] dataset: choose the vector component (channel, dim=3) and then the
		// z-slice (dim=2). Slicing the higher dimension (channel) first keeps the z index valid at dim=2.
		final RandomAccessibleInterval<FloatType> slice = Views.hyperSlice(
				Views.hyperSlice(cleaned, 3, xory), 2, this.fieldZIndex);

		// Place the slice in world coordinates: field index 0 lands on offset, one field pixel spans scale
		// full-resolution pixels, so a query at p reads the field at (p - offset) / scale.
		final AffineTransform2D fieldToWorld = new AffineTransform2D();
		fieldToWorld.set(this.scale, 0, this.offset[0],
						 0, this.scale, this.offset[1]);
		final RealRandomAccessible<FloatType> scaledAndInterpolated = RealViews.affine(
				Views.interpolate(Views.extendMirrorDouble(slice), new NLinearInterpolatorFactory<>()),
				fieldToWorld);

		// Negate and scale in one pass, applied after interpolation. Negating just flips the vectors; the actual
		// inversion (evaluating at the target rather than the source) happens in applyInPlace.
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

        // The (negated) field vector belongs to the target location, not to the queried source location, so the
        // target solves t - d(t) = source. With bilinear interpolation, t - d(t) is bilinear within each field cell,
        // so each cell can be solved exactly; search the cells in growing square rings around the cell of the first
        // fixed-point iterate source + d(source), which usually holds the root. A root always exists, as t - d(t) is
        // the identity plus a bounded field; where the field folds there are several and the nearest ring's wins.
        final double[] start = new double[2];
        lookUpVector(location, start);
        final long startCellX = (long) Math.floor((location[0] + start[0] - offset[0]) / scale);
        final long startCellY = (long) Math.floor((location[1] + start[1] - offset[1]) / scale);
        for (int r = 0; r <= MAX_SEARCH_RING; r++) {
            for (int i = -r; i <= r; i++) {
                // on the ring's top and bottom rows take every cell, in between only its two ends
                final int jStep = ((Math.abs(i) == r) || (r == 0)) ? 1 : 2 * r;
                for (int j = -r; j <= r; j += jStep) {
                    if (solveInCell(startCellX + i, startCellY + j, location)) {
                        return;
                    }
                }
            }
        }

        // Keep the fixed-point estimate rather than failing the render, logged once per instance to avoid a line
        // per pixel.
        if (! divergenceLogged) {
            divergenceLogged = true;
            LOG.warn("applyInPlace: no inverse found within {} field pixels of ({}, {}) for field {}; the " +
                     "first-order estimate is used. Further occurrences for this transform instance are not logged.",
                     MAX_SEARCH_RING, location[0], location[1], toDataString());
        }
        location[0] += start[0];
        location[1] += start[1];
    }

    /**
     * Solves t - d(t) = location within field cell (cellX, cellY), where t - d(t) is the bilinear map
     * a + b u + c v + e u v of the cell coordinates (u, v) in [0, 1]^2. Eliminating u leaves a quadratic in v.
     *
     * @return whether the cell holds a solution, which then replaces location.
     */
    private boolean solveInCell(final long cellX, final long cellY, final double[] location) {
        // t - d(t) at the corners (0,0), (1,0), (0,1), (1,1), as x and y
        final double x0 = offset[0] + cellX * scale;
        final double y0 = offset[1] + cellY * scale;
        final double[] corners = new double[8];
        final double[] node = new double[2];
        final double[] vector = new double[2];
        for (int k = 0; k < 4; k++) {
            node[0] = x0 + (k & 1) * scale;
            node[1] = y0 + (k >> 1) * scale;
            lookUpVector(node, vector);
            corners[2 * k] = node[0] - vector[0];
            corners[2 * k + 1] = node[1] - vector[1];
        }
        final double hx = location[0] - corners[0], hy = location[1] - corners[1];
        final double bx = corners[2] - corners[0], by = corners[3] - corners[1];
        final double cx = corners[4] - corners[0], cy = corners[5] - corners[1];
        final double ex = corners[6] - corners[4] - corners[2] + corners[0];
        final double ey = corners[7] - corners[5] - corners[3] + corners[1];

        // b u + c v + e u v = h; crossing with (b + e v) eliminates u: k2 v^2 + k1 v + k0 = 0
        final double k2 = ex * cy - ey * cx;
        final double k1 = bx * cy - by * cx + hx * ey - hy * ex;
        final double k0 = hx * by - hy * bx;
        final double discriminant = k1 * k1 - 4 * k2 * k0;
        if (discriminant < 0) {
            return false;
        }

        // the cancellation-free pair of roots, which also covers the linear case k2 = 0 (the first is then infinite)
        final double q = -0.5 * (k1 + Math.copySign(Math.sqrt(discriminant), k1));
        for (final double v : new double[] { q / k2, k0 / q }) {
            if ((v < -CELL_EPSILON) || (v > 1 + CELL_EPSILON)) {
                continue; // also skips NaN
            }
            final double ux = bx + ex * v, uy = by + ey * v;
            final double u = (Math.abs(ux) > Math.abs(uy)) ? (hx - cx * v) / ux : (hy - cy * v) / uy;
            if ((u >= -CELL_EPSILON) && (u <= 1 + CELL_EPSILON)) {
                location[0] = x0 + u * scale;
                location[1] = y0 + v * scale;
                return true;
            }
        }
        return false;
    }

    /**
     * Looks up the interpolated field vector (already negated and scaled to full resolution) at a world location.
     * Package private so that tests can check the field placement on its own, separately from the inversion.
     */
    void lookUpVector(final double[] location, final double[] vector) {
        vector[0] = displacementX.setPositionAndGet(location).getRealDouble();
        vector[1] = displacementY.setPositionAndGet(location).getRealDouble();
    }

    /**
     * Parses the data string (field source URI plus {@code ?key=value} params, e.g.
     * {@code file:///path/to/field.n5?z=5&scale=40.0&offset=-5318.0,-783.0}) and loads the field. Only
     * {@code z} is required; the rest default to the identity placement. Unknown parameters are rejected so a
     * misspelled one can't silently fall back to its default.
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
                    "transform data must be a field source URI followed by '?z=<int>' and optionally " +
                    "'&scale=<double>&offset=<double>,<double>&vectorScale=<double>', " +
                    "but was '" + data + "'");
        }

        final String parsedSourceUri = trimmed.substring(0, queryStart);
        final QueryKeyValueParameters params = new QueryKeyValueParameters(trimmed.substring(queryStart + 1), data);
        params.validateKeys(VALID_PARAMETERS);

        final Optional<double[]> parsedOffset = params.getDoubleArray("offset");
        if (parsedOffset.isPresent() && (parsedOffset.get().length != 2)) {
            throw new IllegalArgumentException(
                    "parameter 'offset' must be two comma separated numbers, but was '" +
                    params.getString("offset").orElseThrow() + "' in transform data '" + data + "'");
        }
        final double[] offset = parsedOffset.orElse(new double[] { DEFAULT_OFFSET, DEFAULT_OFFSET });

        init(parsedSourceUri,
             params.getInt("z").orElseThrow(() -> new IllegalArgumentException("missing required parameter 'z' in transform data '" + data + "'")),
             params.getDouble("scale").orElse(DEFAULT_SCALE),
             offset,
             params.getDouble("vectorScale").orElse(DEFAULT_VECTOR_SCALE));
    }

    @Override
    public String toXML(final String indent) {
        return indent + "<ict_transform class=\"" + this.getClass().getCanonicalName() +
               "\" data=\"" + toDataString() + "\"/>";
    }

    @Override
    public String toDataString() {
        // Writes every parameter, even defaults, so a persisted string keeps its meaning if a default ever changes.
        return fieldSourceUri +
               "?z=" + fieldZIndex +
               "&scale=" + scale +
               "&offset=" + offset[0] + "," + offset[1] +
               "&vectorScale=" + vectorScale;
    }

    @Override
    public CoordinateTransform copy() {
        // Re-loads the field so the copy has independent accessors (imglib2 accessors are not thread-safe).
        return new DisplacementFieldTransform(fieldSourceUri, fieldZIndex, scale, offset.clone(), vectorScale);
    }

    @Override
    public String toString() {
        return "{ \"fieldSourceUri\": \"" + fieldSourceUri +
               "\", \"fieldZIndex\": " + fieldZIndex +
               ", \"scale\": " + scale +
               ", \"offset\": [" + offset[0] + ", " + offset[1] + "]" +
               ", \"vectorScale\": " + vectorScale + " }";
    }

    private static final Logger LOG = LoggerFactory.getLogger(DisplacementFieldTransform.class);

    private static final double DEFAULT_SCALE = 1.0;
    private static final double DEFAULT_OFFSET = 0.0;
    private static final double DEFAULT_VECTOR_SCALE = 1.0;

    /** Rings of field cells that {@link #applyInPlace} searches for the inverse; must cover the distance from the
     *  first-order estimate to the root, which is at most twice the largest displacement, in field pixels. */
    private static final int MAX_SEARCH_RING = 32;
    /** Slack on the cell bounds in {@link #solveInCell}, so a root on a shared edge isn't lost to rounding. */
    private static final double CELL_EPSILON = 1e-9;

    private static final Set<String> VALID_PARAMETERS = Set.of("z", "scale", "offset", "vectorScale");
}

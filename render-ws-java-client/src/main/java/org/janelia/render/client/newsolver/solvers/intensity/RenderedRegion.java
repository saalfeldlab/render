package org.janelia.render.client.newsolver.solvers.intensity;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.real.FloatType;

/**
 * A rendered region of a tile for intensity matching, exposed as {@link RandomAccessibleInterval}s bounded to the
 * compared region.
 * <p>
 * The three rasters are produced by the same mesh mapping and share dimensions, so they are pixel-aligned with each
 * other:
 * <ul>
 *     <li>{@link #image} &ndash; intensities mapped to [0, 1];</li>
 *     <li>{@link #weight} &ndash; alpha/mask weight in [0, 1];</li>
 *     <li>{@link #coefficients} &ndash; the sub-tile label of each pixel (1-based; 0 = outside),
 *         identifying which of the {@code numCoefficients x numCoefficients} correction cells the
 *         pixel belongs to.</li>
 * </ul>
 * <p>
 * When both tiles of a pair are rendered by the same {@link TileRenderer} for the same region, the
 * two {@code RenderedRegion}s have identical dimensions and their flat iteration orders line up, so a
 * match is a lockstep walk over the two sets of rasters &ndash; no interpolation or re-indexing. The
 * rasters may be zero-copy views (e.g. {@code Views.interval} crops of a cached full-tile render),
 * so callers should treat them as read-only.
 */
record RenderedRegion(RandomAccessibleInterval<FloatType> image,
                      RandomAccessibleInterval<FloatType> weight,
                      RandomAccessibleInterval<IntType> coefficients) {

    public int getWidth() {
        return (int) image.dimension(0);
    }

    public int getHeight() {
        return (int) image.dimension(1);
    }
}

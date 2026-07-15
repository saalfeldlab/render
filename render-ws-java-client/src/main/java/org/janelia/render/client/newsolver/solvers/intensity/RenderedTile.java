package org.janelia.render.client.newsolver.solvers.intensity;

import ij.process.ColorProcessor;
import ij.process.FloatProcessor;

/**
 * A tile rendered once (at a fixed scale) for intensity matching, together with its position on the
 * shared scale-space pixel grid.
 * <p>
 * The three rasters are all produced by the same mesh mapping, so they are pixel-aligned with each
 * other:
 * <ul>
 *     <li>{@link #image} &ndash; intensities mapped to [0, 1];</li>
 *     <li>{@link #weight} &ndash; alpha/mask weight in [0, 1];</li>
 *     <li>{@link #coefficients} &ndash; the sub-tile label of each pixel (1-based; 0 = outside),
 *         identifying which of the {@code numCoefficients x numCoefficients} correction cells the
 *         pixel belongs to.</li>
 * </ul>
 * <p>
 * {@link #startX}/{@link #startY} are the position of the raster's top-left pixel on a single world
 * grid shared by all tiles rendered at the same scale: buffer pixel {@code (col, row)} corresponds
 * to world coordinate {@code ((startX + col) / scale, (startY + row) / scale)}. Because every tile
 * uses the same world-origin-phased grid, two rasters (rendered at the same scale) that overlap in
 * world space can be compared by a plain integer-offset crop, with no interpolation: for an absolute
 * grid column {@code C}, the local column in this raster is simply {@code C - startX}.
 */
class RenderedTile {

	public final FloatProcessor image;
	public final FloatProcessor weight;
	public final ColorProcessor coefficients;

	/** Column of the raster's top-left pixel on the shared scale-space grid (in downsampled pixels). */
	public final int startX;
	/** Row of the raster's top-left pixel on the shared scale-space grid (in downsampled pixels). */
	public final int startY;

	RenderedTile(final FloatProcessor image,
				 final FloatProcessor weight,
				 final ColorProcessor coefficients,
				 final int startX,
				 final int startY) {
		this.image = image;
		this.weight = weight;
		this.coefficients = coefficients;
		this.startX = startX;
		this.startY = startY;
	}

	public int getWidth() {
		return image.getWidth();
	}

	public int getHeight() {
		return image.getHeight();
	}
}

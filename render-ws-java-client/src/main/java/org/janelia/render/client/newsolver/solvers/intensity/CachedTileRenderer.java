package org.janelia.render.client.newsolver.solvers.intensity;

import java.awt.Rectangle;

import net.imglib2.view.Views;

import org.janelia.alignment.spec.TileSpec;

/**
 * {@link TileRenderer} that renders each tile's full footprint once, caches the result, and serves
 * every overlap pair from a zero-copy crop of that cached render.
 * <p>
 * On a cache miss the tile's entire bounding box is loaded, filtered, downsampled and meshed into the
 * intensity/weight/label rasters ({@link #loadSource} + {@link #renderBox}), and the resulting
 * {@link RenderedTile} is cached keyed by tile id. Each subsequent {@link #render} for the tile (once
 * per overlap pair) returns a {@link Views#interval} crop of the cached rasters covering the requested
 * box &ndash; no meshing or interpolation. This removes all per-pair rendering, at the cost of
 * materializing whole tiles rather than just the overlap boxes.
 * <p>
 * Each tile is rendered on a pixel grid anchored at its own bounding-box origin, so the crops of two
 * partner tiles can be offset from one another by up to one (downsampled) pixel &ndash; unlike
 * {@link TightBoxTileRenderer}, which renders both partners on the shared overlap-box grid. At match
 * scales this sub-pixel difference is negligible.
 * <p>
 * Caching uses a {@link TileCache} bounded by rendered pixel number, which is instance-scoped.
 */
class CachedTileRenderer extends TileRenderer {

	// image (float) + weight (float) + labels (int) = 12 bytes per rendered pixel
	private static final long BYTES_PER_PIXEL = 12L;

	private final TileCache<RenderedTile> tileCache;

	/**
	 * @param numCoefficients number of coefficients in the intensity model
	 * @param scale render scale
	 * @param meshResolution resolution of the mesh used to render the tile
	 * @param maximumCachedKilobytes maximum size of the cache for full-tile renders
	 */
	CachedTileRenderer(final int numCoefficients,
					   final double scale,
					   final int meshResolution,
					   final long maximumCachedKilobytes) {
		super(numCoefficients, scale, meshResolution);
		this.tileCache = new TileCache<>(
				maximumCachedKilobytes,
				tile -> (int) Math.min(Integer.MAX_VALUE, kilobytesOf(tile)),
				this::renderFullTile);
	}

	@Override
	RenderedTile render(final TileSpec patch, final Rectangle box) {
		final RenderedTile fullTile = tileCache.get(patch);
		return crop(fullTile, boundingBox(patch), box);
	}

	/** Cache loader: render the tile's entire footprint once. */
	private RenderedTile renderFullTile(final TileSpec patch) {
		final DownsampledSource source = loadSource(patch);
		return renderBox(patch, source, boundingBox(patch));
	}

	/**
	 * Return a zero-copy crop of the full-tile render covering the world-space {@code box}. The crop
	 * is {@code round(box.width * scale) x round(box.height * scale)} pixels, matching what
	 * {@link #renderBox} would produce for {@code box} directly, so both partner tiles of a pair yield
	 * identically sized, lockstep-iterable rasters.
	 */
	private RenderedTile crop(final RenderedTile fullTile, final Rectangle tileBox, final Rectangle box) {
		final int w = (int) Math.round(box.width * scale);
		final int h = (int) Math.round(box.height * scale);

		// offset of the box within the full tile render; both are anchored at the tile box origin
		int x = (int) Math.round((box.x - tileBox.x) * scale);
		int y = (int) Math.round((box.y - tileBox.y) * scale);

		// Keep the w x h window inside the full render. Since box is contained in the tile box, the
		// full render is at least w x h, so only the offset (not the size) can need clamping; this also
		// guards against off-by-one rounding at the far edge.
		x = Math.max(0, Math.min(x, fullTile.getWidth() - w));
		y = Math.max(0, Math.min(y, fullTile.getHeight() - h));

		final long[] min = {x, y};
		final long[] max = {x + w - 1L, y + h - 1L};
		return new RenderedTile(
				Views.zeroMin(Views.interval(fullTile.image, min, max)),
				Views.zeroMin(Views.interval(fullTile.weight, min, max)),
				Views.zeroMin(Views.interval(fullTile.coefficients, min, max)));
	}

	private static Rectangle boundingBox(final TileSpec patch) {
		return patch.toTileBounds().toRectangle();
	}

	private static long kilobytesOf(final RenderedTile tile) {
		return (long) tile.getWidth() * tile.getHeight() * BYTES_PER_PIXEL / 1000L;
	}
}

package org.janelia.render.client.newsolver.solvers.intensity;

import java.awt.Rectangle;

import org.janelia.alignment.spec.TileSpec;

/**
 * {@link TileRenderer} that renders exactly the requested box on every call, but caches the
 * pair-independent part of the render: the filtered, downsampled source (see {@link TileRasterizer}).
 * Filtering and downsampling therefore happen once per tile, while meshing and mapping are redone for
 * each overlap box.
 * <p>
 * Caching uses a {@link TileCache} bounded by (downsampled) source size, which is instance-scoped.
 */
class TightBoxTileRenderer implements TileRenderer {

	private final TileRasterizer rasterizer;
	private final TileCache<DownsampledSource> sourceCache;

	/**
	 * @param numCoefficients number of coefficients in the intensity model
	 * @param scale render scale
	 * @param meshResolution resolution of the mesh used to render the box
	 * @param maximumCachedKilobytes maximum size of the cache for downsampled sources
	 */
	TightBoxTileRenderer(final int numCoefficients,
						 final double scale,
						 final int meshResolution,
						 final long maximumCachedKilobytes) {
		this.rasterizer = new TileRasterizer(numCoefficients, scale, meshResolution);
		this.sourceCache = new TileCache<>(
				maximumCachedKilobytes,
				source -> (int) Math.min(Integer.MAX_VALUE, source.kilobytes()),
				rasterizer::loadSource);
	}

	@Override
	public RenderedTile render(final TileSpec patch, final Rectangle box) {
		final DownsampledSource source = sourceCache.get(patch);
		return rasterizer.renderBox(patch, source, box);
	}
}

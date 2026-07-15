package org.janelia.render.client.newsolver.solvers.intensity;

import java.awt.Rectangle;

import org.janelia.alignment.spec.TileSpec;

/**
 * Renders a tile into the intensity, weight and sub-tile-label rasters used for intensity matching
 * (see {@link RenderedTile}).
 * <p>
 * Implementations may differ in how much of the tile they materialize and whether renders are reused
 * across calls; see the concrete implementations for their respective tradeoffs.
 */
interface TileRenderer {

	/**
	 * Renders the given tile, covering at least the given world-space region.
	 *
	 * @param patch the tile to render
	 * @param box   the world-space region of interest (e.g. the intersection with the partner tile);
	 *              an implementation may render exactly this region or the tile's full footprint
	 * @return the rendered rasters together with their position on the shared scale-space grid
	 */
	RenderedTile render(TileSpec patch, Rectangle box);
}

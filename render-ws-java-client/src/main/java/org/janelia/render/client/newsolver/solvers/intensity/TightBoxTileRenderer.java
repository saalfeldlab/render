package org.janelia.render.client.newsolver.solvers.intensity;

import ij.process.ColorProcessor;
import ij.process.FloatProcessor;

import java.awt.Rectangle;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.real.FloatType;

import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.intensityadjust.intensity.Render;

/**
 * {@link TileRenderer} that renders exactly the requested box on every call, delegating to
 * {@link Render#render}. This reproduces the original (pre-optimization) matching behavior.
 * <p>
 * Nothing is cached between calls, so a tile shared by several overlap pairs is re-rendered once per
 * pair. In exchange, it never renders more than the region actually compared, which makes it a good
 * fit when tiles participate in few pairs or overlap only in thin strips (e.g. in-plane-only
 * matching at large render scale).
 */
class TightBoxTileRenderer implements TileRenderer {

	private final int numCoefficients;
	private final double scale;
	private final int meshResolution;
	private final ImageProcessorCache imageProcessorCache;

	TightBoxTileRenderer(final int numCoefficients,
						 final double scale,
						 final int meshResolution,
						 final ImageProcessorCache imageProcessorCache) {
		this.numCoefficients = numCoefficients;
		this.scale = scale;
		this.meshResolution = meshResolution;
		this.imageProcessorCache = imageProcessorCache;
	}

	@Override
	public RenderedTile render(final TileSpec patch, final Rectangle box) {
		final int w = (int) Math.round(box.width * scale);
		final int h = (int) Math.round(box.height * scale);

		final FloatProcessor image = new FloatProcessor(w, h);
		final FloatProcessor weight = new FloatProcessor(w, h);
		final ColorProcessor coefficients = new ColorProcessor(w, h);

		Render.render(patch, numCoefficients, numCoefficients, image, weight, coefficients,
					  box.x, box.y, scale, meshResolution, imageProcessorCache);

		// wrap the just-rendered pixel arrays as image views (zero-copy)
		final RandomAccessibleInterval<FloatType> imageView = ArrayImgs.floats((float[]) image.getPixels(), w, h);
		final RandomAccessibleInterval<FloatType> weightView = ArrayImgs.floats((float[]) weight.getPixels(), w, h);
		final RandomAccessibleInterval<IntType> coefficientsView = ArrayImgs.ints((int[]) coefficients.getPixels(), w, h);

		return new RenderedTile(imageView, weightView, coefficientsView);
	}
}

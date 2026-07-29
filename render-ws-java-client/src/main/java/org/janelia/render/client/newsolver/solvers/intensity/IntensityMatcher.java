package org.janelia.render.client.newsolver.solvers.intensity;

import java.awt.Rectangle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import mpicbg.models.PointMatch;
import mpicbg.models.Tile;

import net.imglib2.Cursor;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMIntensityCorrectionParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.imglib2.util.StopWatch;


/*
 * Class for matching the intensity of two tiles. After matching, the tiles are
 * connected and the matches are filtered. Tiles within the same layer (i.e.,
 * partially overlapping) can be downscaled differently from tiles in different
 * layers (i.e., almost completely overlapping).
 * Also, computation of averages for a tile is done here.
 * <p>
 * Caching is done for each scale (same-layer vs. cross-layer) independently, unless
 * the scales are the same, in which case the same cache is shared for both.
 */
class IntensityMatcher {
	// separate filters per layer relationship: a per-pixel cutoff (when configured) only applies to cross-layer pairs
	final private MatchFilter sameLayerFilter;
	final private MatchFilter crossLayerFilter;
	final private int numCoefficients;
	final private TileRenderer sameLayerRenderer;
	final private TileRenderer crossLayerRenderer;

	public IntensityMatcher(
			final MatchFilter sameLayerFilter,
			final MatchFilter crossLayerFilter,
			final FIBSEMIntensityCorrectionParameters<?> parameters,
			final int meshResolution,
			final long maximumCachedKilobytes) {
		this.sameLayerFilter = sameLayerFilter;
		this.crossLayerFilter = crossLayerFilter;
		this.numCoefficients = parameters.numCoefficients();
		final boolean cacheRenderedTiles = parameters.cacheRenderedTiles();

		final double sameLayerScale = parameters.renderScale();
		this.sameLayerRenderer = createRenderer(sameLayerScale, meshResolution, maximumCachedKilobytes, cacheRenderedTiles);

		// Share rendering effort if scale is the same
		final double crossLayerScale = parameters.crossLayerRenderScale();
		if (sameLayerScale == crossLayerScale) {
			this.crossLayerRenderer = sameLayerRenderer;
		} else {
			this.crossLayerRenderer = createRenderer(crossLayerScale, meshResolution, maximumCachedKilobytes, cacheRenderedTiles);
		}
	}

	/**
	 * Create the configured renderer for the given scale. {@link CachedTileRenderer} renders each tile's whole
	 * footprint once and crops overlaps from it (less compute, more memory); {@link TightBoxTileRenderer} caches
	 * only the downsampled source and re-renders each overlap box (more compute, less memory).
	 */
	private TileRenderer createRenderer(final double scale,
										final int meshResolution,
										final long maximumCachedKilobytes,
										final boolean cacheRenderedTiles) {
		return cacheRenderedTiles
				? new CachedTileRenderer(numCoefficients, scale, meshResolution, maximumCachedKilobytes)
				: new TightBoxTileRenderer(numCoefficients, scale, meshResolution, maximumCachedKilobytes);
	}

	public void match(final String renderStack,
                      final TileSpec p1,
                      final TileSpec p2,
                      final HashMap<String, IntensityTile> intensityTiles) {

		final StopWatch stopWatch = StopWatch.createAndStart();

		final boolean crossLayer = (p1.zDistanceFrom(p2) != 0);
		final TileRenderer renderer = crossLayer ? crossLayerRenderer : sameLayerRenderer;
		final MatchFilter filter = crossLayer ? crossLayerFilter : sameLayerFilter;
		final Rectangle box = computeIntersection(p1, p2);

		// Both tiles are rendered for the same region by the same renderer, so the two sets of rasters
		// share dimensions and their flat iteration orders refer to the same world locations.
		final RenderedTile r1 = renderer.render(p1, box);
		final RenderedTile r2 = renderer.render(p2, box);
		final int n = r1.getWidth() * r1.getHeight();

		// Generate a matrix of all coefficients in p1 to all coefficients in p2 to store matches
		final int nCoefficientTiles = numCoefficients * numCoefficients;
		final List<FlatIntensityMatches> pairwiseCoefficients = getPairwiseCoefficients(n, nCoefficientTiles);

		// Iterate over all pixels in lockstep and feed matches into the match matrix
		final Cursor<IntType> label1Cursor = Views.flatIterable(r1.coefficients).cursor();
		final Cursor<IntType> label2Cursor = Views.flatIterable(r2.coefficients).cursor();
		final Cursor<FloatType> weight1Cursor = Views.flatIterable(r1.weight).cursor();
		final Cursor<FloatType> weight2Cursor = Views.flatIterable(r2.weight).cursor();
		final Cursor<FloatType> pixel1Cursor = Views.flatIterable(r1.image).cursor();
		final Cursor<FloatType> pixel2Cursor = Views.flatIterable(r2.image).cursor();

		while (label1Cursor.hasNext()) {
			final int label1 = label1Cursor.next().get();
			final int label2 = label2Cursor.next().get();
			final float weight1 = weight1Cursor.next().get();
			final float weight2 = weight2Cursor.next().get();
			final float p = pixel1Cursor.next().get();
			final float q = pixel2Cursor.next().get();

			if (label1 > 0 && label2 > 0 && weight1 > 0 && weight2 > 0) {
				// First sub-tile label is 1 -> adjust to 0-based indexing
				final FlatIntensityMatches matches = pairwiseCoefficients.get((label1 - 1) * nCoefficientTiles + (label2 - 1));
				matches.put(p, q, weight1 * weight2);
			}
		}

		// Filter matches
		final List<List<PointMatch>> filteredMatches = new ArrayList<>();
		for (final FlatIntensityMatches coefficientsForPair : pairwiseCoefficients) {
			if (coefficientsForPair.isEmpty()) {
				filteredMatches.add(new ArrayList<>());
				continue;
			}

            final List<PointMatch> filteredMatchesForPair;
            try {
                filteredMatchesForPair = filter.filter(coefficientsForPair);
            } catch (final IOException e) {
                throw new RuntimeException("failed to filter coefficients for pair " + p1 + " (z " + p1.getZ() + "), " +
                                           p2 + " (z " + p2.getZ() + ") in " + renderStack, e);
            }
            filteredMatches.add(filteredMatchesForPair);
		}

		// Connect tiles across patches
		final IntensityTile p1IntensityTile = intensityTiles.get(p1.getTileId());
		final IntensityTile p2IntensityTile = intensityTiles.get(p2.getTileId());
		int connectionCount = 0;

		for (int i = 0; i < nCoefficientTiles; ++i) {
			final Tile<?> t1 = p1IntensityTile.getSubTile(i);

			for (int j = 0; j < nCoefficientTiles; ++j) {
				final List<PointMatch> matches = filteredMatches.get(i * nCoefficientTiles + j);
				if (matches.isEmpty())
					continue;

				final Tile<?> t2 = p2IntensityTile.getSubTile(j);
				t1.connect(t2, matches);
				connectionCount++;
			}
		}

		if (connectionCount > 0) {
			p1IntensityTile.connectTo(p2IntensityTile);
		}

		stopWatch.stop();
		LOG.debug("match: pair {} <-> {} has {} connections, matching took {}", p1.getTileId(), p2.getTileId(), connectionCount, stopWatch);
	}

	private static List<FlatIntensityMatches> getPairwiseCoefficients(
			final int maxMatchesPerPair,
			final int nCoefficientTiles
	) {
		final int nPairs = nCoefficientTiles * nCoefficientTiles;
		final List<FlatIntensityMatches> coefficients = new ArrayList<>(nPairs);
		for (int i = 0; i < nPairs; ++i) {
			coefficients.add(new FlatIntensityMatches(maxMatchesPerPair));
		}
		return coefficients;
	}

	List<Double> computeAverages(final TileSpec tile) {

		final StopWatch stopWatch = StopWatch.createAndStart();

		final RenderedTile rendered = sameLayerRenderer.render(tile, boundingBox(tile));

		final float[] averages = new float[numCoefficients * numCoefficients];
		final int[] counts = new int[numCoefficients * numCoefficients];

		final Cursor<IntType> labelCursor = Views.flatIterable(rendered.coefficients).cursor();
		final Cursor<FloatType> pixelCursor = Views.flatIterable(rendered.image).cursor();
		while (labelCursor.hasNext()) {
			final int label = labelCursor.next().get();
			final float p = pixelCursor.next().get();

			/* first label is 1 */
			if (label > 0) {
				averages[label - 1] += p;
				counts[label - 1]++;
			}
		}

		final List<Double> result = new ArrayList<>(averages.length);
		for (int i = 0; i < averages.length; ++i)
			result.add((double) (averages[i] / counts[i]));

		stopWatch.stop();
		LOG.info("computeAverages: tile {} took {}", tile.getTileId(), stopWatch);
		return result;
	}

	private static Rectangle computeIntersection(final TileSpec p1, final TileSpec p2) {
		final Rectangle box1 = boundingBox(p1);
		final Rectangle box2 = boundingBox(p2);
		return box1.intersection(box2);
	}

	static Rectangle boundingBox(final TileSpec tileSpec) {
		return tileSpec.toTileBounds().toRectangle();
	}

	private static final Logger LOG = LoggerFactory.getLogger(IntensityMatcher.class);
}

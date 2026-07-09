package org.janelia.render.client.newsolver.solvers.intensity;

import ij.process.ColorProcessor;
import ij.process.FloatProcessor;

import java.awt.Rectangle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import mpicbg.models.PointMatch;
import mpicbg.models.Tile;

import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.intensityadjust.intensity.Render;
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
 */
class IntensityMatcher {
	final private MatchFilter filter;
	final private double sameLayerScale;
	final private double crossLayerScale;
	final private int numCoefficients;
	final int meshResolution;
	final ImageProcessorCache imageProcessorCache;
	// cutoff for cross-layer matches, expressed in normalized [0,1] intensity units (null cutoff => disabled)
	final private boolean applyCutoff;
	final private double cutoffShiftNormalized;

	public IntensityMatcher(
			final MatchFilter filter,
			final FIBSEMIntensityCorrectionParameters<?> parameters,
			final int meshResolution,
			final ImageProcessorCache imageProcessorCache) {
		this.filter = filter;
		this.sameLayerScale = parameters.renderScale();
		this.crossLayerScale = parameters.crossLayerRenderScale();
		this.numCoefficients = parameters.numCoefficients();
		this.meshResolution = meshResolution;
		this.imageProcessorCache = imageProcessorCache;

		// the cutoff is given in 8-bit gray levels but compared against intensities normalized to [0,1]
		final Double cutoffDifference = parameters.cutoffDifference();
		this.applyCutoff = (cutoffDifference != null);
		this.cutoffShiftNormalized = this.applyCutoff ? (cutoffDifference / 255.0) : 0.0;
	}

	public void match(final String renderStack,
                      final TileSpec p1,
                      final TileSpec p2,
                      final HashMap<String, IntensityTile> intensityTiles) {

		final StopWatch stopWatch = StopWatch.createAndStart();

		final boolean crossLayer = (p1.zDistanceFrom(p2) != 0);
		final double scale = crossLayer ? crossLayerScale : sameLayerScale;
		final Rectangle box = computeIntersection(p1, p2);
		final int w = numberOfPixels(box.width, scale);
		final int h = numberOfPixels(box.height, scale);
		final int n = w * h;

		final FloatProcessor pixels1 = new FloatProcessor(w, h);
		final FloatProcessor weights1 = new FloatProcessor(w, h);
		final ColorProcessor subTiles1 = new ColorProcessor(w, h);
		final FloatProcessor pixels2 = new FloatProcessor(w, h);
		final FloatProcessor weights2 = new FloatProcessor(w, h);
		final ColorProcessor subTiles2 = new ColorProcessor(w, h);

		Render.render(p1, numCoefficients, numCoefficients, pixels1, weights1, subTiles1, box.x, box.y, scale, meshResolution, imageProcessorCache);
		Render.render(p2, numCoefficients, numCoefficients, pixels2, weights2, subTiles2, box.x, box.y, scale, meshResolution, imageProcessorCache);

		// Generate a matrix of all coefficients in p1 to all coefficients in p2 to store matches
		final int nCoefficientTiles = numCoefficients * numCoefficients;
		final List<FlatIntensityMatches> pairwiseCoefficients = getPairwiseCoefficients(w * h, nCoefficientTiles);

		// Iterate over all pixels and feed matches into the match matrix
		int label1, label2 = 0;
		float weight1 = 0, weight2 = 0;
		for (int k = 0; k < n; ++k) {
			// Lazily check if it pays to create a match
			final boolean matchCanContribute = (label1 = subTiles1.get(k)) > 0
					&& (label2 = subTiles2.get(k)) > 0
					&& (weight1 = weights1.getf(k)) > 0
					&& (weight2 = weights2.getf(k)) > 0;

			if (matchCanContribute) {
				final double p = pixels1.getf(k);
				final double q = pixels2.getf(k);

				// First sub-tile label is 1 -> adjust to 0-based indexing
				final int i = label1 - 1;
				final int j = label2 - 1;
				final FlatIntensityMatches matches = pairwiseCoefficients.get(i * nCoefficientTiles + j);
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

            // suppress bad cross-layer matches (e.g. at tissue-to-resin transitions in z) by zeroing their weight
            if (applyCutoff && crossLayer)
                zeroWeightIfShiftExceedsCutoff(filteredMatchesForPair);

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

	/**
	 * Computes the mean intensity shift of the (already reduced) matches for a single coefficient-tile pair and,
	 * if it exceeds the configured cutoff, sets the weight of all these matches to 0 so that they do not contribute
	 * to the solve. Both point locations are stored in normalized [0,1] intensity units, so the shift is compared
	 * against the (likewise normalized) cutoff. The matches are left in place (rather than removed) to honor the
	 * "set weight to 0" semantics.
	 */
	private void zeroWeightIfShiftExceedsCutoff(final List<PointMatch> matches) {
		if (matches.isEmpty())
			return;

		double shiftSum = 0.0;
		for (final PointMatch match : matches)
			shiftSum += match.getP2().getL()[0] - match.getP1().getL()[0];
		final double meanShift = Math.abs(shiftSum / matches.size());

		if (meanShift > cutoffShiftNormalized) {
			for (final PointMatch match : matches)
				match.setWeights(new double[] { 0.0 });
			LOG.debug("zeroWeightIfShiftExceedsCutoff: zeroed {} match(es), meanShift {} > cutoff {} (normalized)",
					  matches.size(), meanShift, cutoffShiftNormalized);
		}
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

	private static int numberOfPixels(final int length, final double scale) {
		return (int) Math.round(length * scale);
	}

	List<Double> computeAverages(final TileSpec tile) {

		final StopWatch stopWatch = StopWatch.createAndStart();
		final Rectangle box = boundingBox(tile);

		final int w = numberOfPixels(box.width, sameLayerScale);
		final int h = numberOfPixels(box.height, sameLayerScale);
		final int n = w * h;

		final FloatProcessor pixels = new FloatProcessor(w, h);
		final FloatProcessor weights = new FloatProcessor(w, h);
		final ColorProcessor subTiles = new ColorProcessor(w, h);

		Render.render(tile, numCoefficients, numCoefficients, pixels, weights, subTiles, box.x, box.y, sameLayerScale, meshResolution, imageProcessorCache);

		final float[] averages = new float[numCoefficients * numCoefficients];
		final int[] counts = new int[numCoefficients * numCoefficients];

		for (int i = 0; i < n; ++i) {
			final int label = subTiles.get(i);

			/* first label is 1 */
			if (label > 0) {
				final float p = pixels.getf(i);
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

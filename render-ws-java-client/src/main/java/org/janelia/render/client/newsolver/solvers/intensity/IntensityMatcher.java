package org.janelia.render.client.newsolver.solvers.intensity;

import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import net.imglib2.util.Pair;
import net.imglib2.util.StopWatch;
import net.imglib2.util.ValuePair;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.intensityadjust.intensity.PointMatchFilter;
import org.janelia.render.client.intensityadjust.intensity.Render;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMIntensityCorrectionParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/*
 * Class for matching the intensity of two tiles. After matching, the tiles are
 * connected and the matches are filtered. Tiles within the same layer (i.e.,
 * partially overlapping) can be downscaled differently from tiles in different
 * layers (i.e., almost completely overlapping).
 * Also, computation of averages for a tile is done here.
 *
 * @author Michael Innerberger
 */
class IntensityMatcher {
	private static final int N_BINS = 256;

	final private PointMatchFilter filter;
	final private double sameLayerScale;
	final private double crossLayerScale;
	final private int numCoefficients;
	final int meshResolution;
	final ImageProcessorCache imageProcessorCache;

	public IntensityMatcher(
			final PointMatchFilter filter,
			final FIBSEMIntensityCorrectionParameters<?> parameters,
			final int meshResolution,
			final ImageProcessorCache imageProcessorCache) {
		this.filter = filter;
		this.sameLayerScale = parameters.renderScale();
		this.crossLayerScale = parameters.crossLayerRenderScale();
		this.numCoefficients = parameters.numCoefficients();
		this.meshResolution = meshResolution;
		this.imageProcessorCache = imageProcessorCache;
	}

	public void match(final TileSpec p1, final TileSpec p2, final HashMap<String, IntensityTile> intensityTiles) {

		final StopWatch stopWatch = StopWatch.createAndStart();

		final double scale = (p1.zDistanceFrom(p2) == 0) ? sameLayerScale : crossLayerScale;
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

		// generate a matrix of all coefficients in p1 to all coefficients in p2 to store matches
		final int nCoefficientTiles = numCoefficients * numCoefficients;
		final List<List<PointMatch>> matrix = getPairwiseCoefficientMatrix(nCoefficientTiles);

		// iterate over all pixels and feed matches into the match matrix
		int label1, label2 = 0;
		float weight1 = 0, weight2 = 0;
		for (int i = 0; i < n; ++i) {
			// lazily check if it pays to create a match
			final boolean matchCanContribute = (label1 = subTiles1.get(i)) > 0
					&& (label2 = subTiles2.get(i)) > 0
					&& (weight1 = weights1.getf(i)) > 0
					&& (weight2 = weights2.getf(i)) > 0;

			if (matchCanContribute) {
				final double p = pixels1.getf(i);
				final double q = pixels2.getf(i);
				final PointMatch pq = new PointMatch1D(new Point1D(p), new Point1D(q), weight1 * weight2);

				/* first sub-tile label is 1 */
				final List<PointMatch> matches = get(matrix, label1 - 1, label2 - 1, nCoefficientTiles);
				matches.add(pq);
			}
		}

		/* filter matches */
		final List<PointMatch> inliers = new ArrayList<>();
		for (final List<PointMatch> candidates : matrix) {
			if (candidates.isEmpty())
				continue;

			final List<PointMatch> compressedCandidates = compressByBinning(candidates, N_BINS);

			inliers.clear();
			filter.filter(compressedCandidates, inliers);
			candidates.clear();
			candidates.addAll(inliers);
		}

		/* connect tiles across patches */
		final IntensityTile p1IntensityTile = intensityTiles.get(p1.getTileId());
		final IntensityTile p2IntensityTile = intensityTiles.get(p2.getTileId());
		int connectionCount = 0;

		for (int i = 0; i < nCoefficientTiles; ++i) {
			final Tile<?> t1 = p1IntensityTile.getSubTile(i);

			for (int j = 0; j < nCoefficientTiles; ++j) {
				final List<PointMatch> matches = get(matrix, i, j, nCoefficientTiles);
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
		LOG.info("match: pair {} <-> {} has {} connections, matching took {}", p1.getTileId(), p2.getTileId(), connectionCount, stopWatch);
	}

	private static List<List<PointMatch>> getPairwiseCoefficientMatrix(final int dimSize) {
		final int matrixSize = dimSize * dimSize;
		final List<List<PointMatch>> coefficients = new ArrayList<>(matrixSize);
		for (int i = 0; i < matrixSize; ++i) {
			coefficients.add(new ArrayList<>());
		}
		return coefficients;
	}

	private static List<PointMatch> get(final List<List<PointMatch>> matrix, final int i, final int j, final int size) {
		return matrix.get(i * size + j);
	}

	private static int numberOfPixels(final int length, final double scale) {
		return (int) Math.round(length * scale);
	}

	// Since there is a good chance that some intensity matches are redundant, we can try to compress them by binning
	private static List<PointMatch> compressByBinning(final List<PointMatch> candidates, final int nBins) {
		final Map<Pair<Integer, Integer>, Double> pairToWeights = new HashMap<>(nBins * nBins);
		for (final PointMatch candidate : candidates) {
			// Use the fact that the intensity matches are integers in the range [0, 255]
			final int x = (int) Math.round(candidate.getP1().getL()[0] * nBins);
			final int y = (int) Math.round(candidate.getP2().getL()[0] * nBins);
			final Pair<Integer, Integer> pair = new ValuePair<>(x, y);
			final double previousWeight = pairToWeights.getOrDefault(pair, 0.0);
			pairToWeights.put(pair, previousWeight + candidate.getWeight());
		}

		if (pairToWeights.size() == candidates.size()) {
			// Compression was not successful
			return candidates;
		}

		final List<PointMatch> compressedCandidates = new ArrayList<>(pairToWeights.size());
		for (final Map.Entry<Pair<Integer, Integer>, Double> entry : pairToWeights.entrySet()) {
			final Pair<Integer, Integer> pair = entry.getKey();
			final double weight = entry.getValue();
			final Point1D p1 = new Point1D((double) pair.getA() / nBins);
			final Point1D p2 = new Point1D((double) pair.getB() / nBins);
			compressedCandidates.add(new PointMatch1D(p1, p2, weight));
		}

		return compressedCandidates;
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

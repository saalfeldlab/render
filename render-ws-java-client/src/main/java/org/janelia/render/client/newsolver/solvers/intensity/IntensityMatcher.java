package org.janelia.render.client.newsolver.solvers.intensity;

import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import mpicbg.models.Affine1D;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import net.imglib2.Interval;
import net.imglib2.img.list.ListImg;
import net.imglib2.img.list.ListRandomAccess;
import net.imglib2.util.Intervals;
import net.imglib2.util.StopWatch;
import net.imglib2.util.ValuePair;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.intensityadjust.intensity.PointMatchFilter;
import org.janelia.render.client.intensityadjust.intensity.Render;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;

class IntensityMatcher implements Runnable {
	//final private Rectangle roi;
	final private ValuePair<TileSpec, TileSpec> patchPair;
	final private HashMap<String, ArrayList<Tile<? extends Affine1D<?>>>> coefficientTiles;
	final private PointMatchFilter filter;
	final private double scale;
	final private int numCoefficients;
	final int meshResolution;
	final ImageProcessorCache imageProcessorCache;

	public IntensityMatcher(
			final ValuePair<TileSpec, TileSpec> patchPair,
			final HashMap<String, ArrayList<Tile<? extends Affine1D<?>>>> coefficientTiles,
			final PointMatchFilter filter,
			final double scale,
			final int numCoefficients,
			final int meshResolution,
			final ImageProcessorCache imageProcessorCache) {
		this.patchPair = patchPair;
		this.coefficientTiles = coefficientTiles;
		this.filter = filter;
		this.scale = scale;
		this.numCoefficients = numCoefficients;
		this.meshResolution = meshResolution;
		this.imageProcessorCache = imageProcessorCache;
	}

	@Override
	public void run() {
		final TileSpec p1 = patchPair.getA();
		final TileSpec p2 = patchPair.getB();

		final StopWatch stopWatch = StopWatch.createAndStart();

		LOG.info("run: entry, pair {} <-> {}", p1.getTileId(), p2.getTileId());

		final Rectangle box = computeIntersection(p1, p2);
		final int w = (int) (box.width * scale + 0.5);
		final int h = (int) (box.height * scale + 0.5);
		final int n = w * h;

		final FloatProcessor pixels1 = new FloatProcessor(w, h);
		final FloatProcessor weights1 = new FloatProcessor(w, h);
		final ColorProcessor subTiles1 = new ColorProcessor(w, h);
		final FloatProcessor pixels2 = new FloatProcessor(w, h);
		final FloatProcessor weights2 = new FloatProcessor(w, h);
		final ColorProcessor subTiles2 = new ColorProcessor(w, h);

		Render.render(p1, numCoefficients, numCoefficients, pixels1, weights1, subTiles1, box.x, box.y, scale, meshResolution, imageProcessorCache);
		Render.render(p2, numCoefficients, numCoefficients, pixels2, weights2, subTiles2, box.x, box.y, scale, meshResolution, imageProcessorCache);

		LOG.info("run: generate matrix for pair {} <-> {} and filter", p1.getTileId(), p2.getTileId());

		/*
		 * generate a matrix of all coefficients in p1 to all
		 * coefficients in p2 to store matches
		 */
		final ArrayList<ArrayList<PointMatch>> list = new ArrayList<>();
		final int dimSize = numCoefficients * numCoefficients;
		final int matrixSize = dimSize * dimSize;
		for (int i = 0; i < matrixSize; ++i) {
			list.add(new ArrayList<>());
		}

		final ListImg<ArrayList<PointMatch>> matrix = new ListImg<>(list, dimSize, dimSize);
		final ListRandomAccess<ArrayList<PointMatch>> ra = matrix.randomAccess();

		/*
		 * iterate over all pixels and feed matches into the match
		 * matrix
		 */
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
				final PointMatch pq = new PointMatch(new mpicbg.models.Point(new double[]{p}), new Point(new double[]{q}), weight1 * weight2);

				/* first sub-tile label is 1 */
				ra.setPosition(label1 - 1, 0);
				ra.setPosition(label2 - 1, 1);
				ra.get().add(pq);
			}
		}

		/* filter matches */
		final ArrayList<PointMatch> inliers = new ArrayList<>();
		for (final ArrayList<PointMatch> candidates : matrix) {
			inliers.clear();
			filter.filter(candidates, inliers);
			candidates.clear();
			candidates.addAll(inliers);
		}

		/* connect tiles across patches */
		final ArrayList<Tile<? extends Affine1D<?>>> p1CoefficientTiles = coefficientTiles.get(p1.getTileId());
		final ArrayList<Tile<? extends Affine1D<?>>> p2CoefficientTiles = coefficientTiles.get(p2.getTileId());
		int connectionCount = 0;

		for (int i = 0; i < dimSize; ++i) {
			final Tile<?> t1 = p1CoefficientTiles.get(i);
			ra.setPosition(i, 0);

			for (int j = 0; j < dimSize; ++j) {
				ra.setPosition(j, 1);
				final ArrayList<PointMatch> matches = ra.get();
				if (matches.isEmpty())
					continue;

				final Tile<?> t2 = p2CoefficientTiles.get(j);
				t1.connect(t2, ra.get());
				connectionCount++;
			}
		}

		stopWatch.stop();
		LOG.info("run: exit, pair {} <-> {} has {} connections, matching took {}", p1.getTileId(), p2.getTileId(), connectionCount, stopWatch);
	}

	private static Rectangle computeIntersection(final TileSpec p1, final TileSpec p2) {
		final Interval i1 = Intervals.smallestContainingInterval(AffineIntensityCorrectionBlockWorker.getBoundingBox(p1));
		final Rectangle box1 = new Rectangle((int) i1.min(0), (int) i1.min(1), (int) i1.dimension(0), (int) i1.dimension(1));
		final Interval i2 = Intervals.smallestContainingInterval(AffineIntensityCorrectionBlockWorker.getBoundingBox(p2));
		final Rectangle box2 = new Rectangle((int) i2.min(0), (int) i2.min(1), (int) i2.dimension(0), (int) i2.dimension(1));
		return box1.intersection(box2);
	}

	private static final Logger LOG = LoggerFactory.getLogger(IntensityMatcher.class);
}

package org.janelia.render.client.intensityadjust.intensity;

import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.IdentityModel;
import mpicbg.models.Model;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TileUtil;
import mpicbg.models.TranslationModel1D;
import net.imglib2.FinalRealInterval;
import net.imglib2.Interval;
import net.imglib2.RealInterval;
import net.imglib2.img.list.ListImg;
import net.imglib2.img.list.ListRandomAccess;
import net.imglib2.util.Intervals;
import net.imglib2.util.ValuePair;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.intensityadjust.InterpolatedQuadraticAffineModel1D;
import org.janelia.render.client.intensityadjust.MinimalTileSpecWrapper;
import org.janelia.render.client.intensityadjust.Quadratic1D;
import org.janelia.render.client.intensityadjust.QuadraticModel1D;
import org.janelia.render.client.intensityadjust.virtual.OnTheFlyIntensityQuadratic;
import spim.Threads;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class QuadraticIntensityMatcher
{
	final private class Matcher implements Runnable
	{
		//final private Rectangle roi;
		final private ValuePair<MinimalTileSpecWrapper, MinimalTileSpecWrapper> patchPair;
		final private HashMap<MinimalTileSpecWrapper, ArrayList<Tile<?>>> coefficientsTiles;
		final private PointMatchFilter filter;
		final private double scale;
		final private int numCoefficients;
		final int meshResolution;
		final ImageProcessorCache imageProcessorCache;

		public Matcher(
				final ValuePair<MinimalTileSpecWrapper, MinimalTileSpecWrapper> patchPair,
				final HashMap<MinimalTileSpecWrapper, ArrayList<Tile<?>>> coefficientsTiles,
				final PointMatchFilter filter,
				final double scale,
				final int numCoefficients,
				final int meshResolution,
				final ImageProcessorCache imageProcessorCache)
		{
			//this.roi = roi;
			this.patchPair = patchPair;
			this.coefficientsTiles = coefficientsTiles;
			this.filter = filter;
			this.scale = scale;
			this.numCoefficients = numCoefficients;
			this.meshResolution = meshResolution;
			this.imageProcessorCache = imageProcessorCache;
		}

		@Override
		public void run()
		{
			final MinimalTileSpecWrapper p1 = patchPair.getA();
			final MinimalTileSpecWrapper p2 = patchPair.getB();

			final Interval i1 = Intervals.smallestContainingInterval(getBoundingBox(p1));
			final Rectangle box1 = new Rectangle((int)i1.min(0), (int)i1.min(1), (int)i1.dimension(0), (int)i1.dimension(1));

			/* get the coefficient tiles */
			final ArrayList<Tile<?>> p1CoefficientsTiles = coefficientsTiles.get(p1);

			/* render intersection */
			final Interval i2 = Intervals.smallestContainingInterval(getBoundingBox(p2));
			final Rectangle box2 = new Rectangle((int)i2.min(0), (int)i2.min(1), (int)i2.dimension(0), (int)i2.dimension(1));
			final Rectangle box = box1.intersection(box2);

			final int w = (int) (box.width * scale + 0.5);
			final int h = (int) (box.height * scale + 0.5);
			final int n = w * h;

			final FloatProcessor pixels1 = new FloatProcessor(w, h);
			final FloatProcessor weights1 = new FloatProcessor(w, h);
			final ColorProcessor coefficients1 = new ColorProcessor(w, h);
			final FloatProcessor pixels2 = new FloatProcessor(w, h);
			final FloatProcessor weights2 = new FloatProcessor(w, h);
			final ColorProcessor coefficients2 = new ColorProcessor(w, h);

			Render.render(p1, numCoefficients, numCoefficients, pixels1, weights1, coefficients1, box.x, box.y, scale, meshResolution, imageProcessorCache);
			Render.render(p2, numCoefficients, numCoefficients, pixels2, weights2, coefficients2, box.x, box.y, scale, meshResolution, imageProcessorCache);

			/*
			 * generate a matrix of all coefficients in p1 to all
			 * coefficients in p2 to store matches
			 */
			final ArrayList<ArrayList<PointMatch>> list = new ArrayList<>();
			for (int i = 0; i < numCoefficients * numCoefficients * numCoefficients * numCoefficients; ++i)
				list.add(new ArrayList<>());
			final ListImg<ArrayList<PointMatch>> matrix = new ListImg<>(list, numCoefficients * numCoefficients, numCoefficients * numCoefficients);
			final ListRandomAccess<ArrayList<PointMatch>> ra = matrix.randomAccess();

			/*
			 * iterate over all pixels and feed matches into the match
			 * matrix
			 */
			for (int i = 0; i < n; ++i) {
				final int c1 = coefficients1.get(i);
				if (c1 <= 0)
					continue;

				final int c2 = coefficients2.get(i);
				if (c2 <= 0)
					continue;

				final double w1 = weights1.getf(i);
				if (w1 <= 0)
					continue;

				final double w2 = weights2.getf(i);
				if (w2 <= 0)
					continue;

				final double p = pixels1.getf(i);
				final double q = pixels2.getf(i);
				final PointMatch pq = new PointMatch(new Point(new double[]{p}), new Point(new double[]{q}), w1 * w2);

				/* first label is 1 */
				ra.setPosition(c1 - 1, 0);
				ra.setPosition(c2 - 1, 1);
				ra.get().add(pq);
			}

			/* filter matches */
			final ArrayList<PointMatch> inliers = new ArrayList<>();
			for (final ArrayList<PointMatch> candidates : matrix)
			{
				inliers.clear();
				filter.filter(candidates, inliers);
				candidates.clear();
				candidates.addAll(inliers);
			}

			/* get the coefficient tiles of p2 */
			final ArrayList<Tile<?>> p2CoefficientsTiles = coefficientsTiles.get(p2);

			/* connect tiles across patches */
			for (int i = 0; i <numCoefficients * numCoefficients; ++i)
			{
				final Tile<?> t1 = p1CoefficientsTiles.get(i);
				ra.setPosition(i, 0);
				for (int j = 0; j < numCoefficients * numCoefficients; ++j)
				{
					ra.setPosition(j, 1);
					final ArrayList<PointMatch> matches = ra.get();
					if (matches.size() > 0)
					{
						final Tile<?> t2 = p2CoefficientsTiles.get(j);
						t1.connect(t2, ra.get());
						System.out.println("Connected patch " + p1.getImageCol() + ", coefficient " + i + "  +  patch " + p2.getImageCol() + ", coefficient " + j + " by " + matches.size() + " samples.");
					}
				}
			}
		}
	}

	public <M extends Model<M> & Quadratic1D<M>> ArrayList<OnTheFlyIntensityQuadratic> match(
			final List<MinimalTileSpecWrapper> patches,
			final double scale,
			final int numCoefficients,
			final double lambda1,
			final double lambda2,
			final double neighborWeight,
			final int iterations,
			final ImageProcessorCache imageProcessorCache) throws InterruptedException, ExecutionException
	{
		final PointMatchFilter filter = new RansacRegressionReduceFilter(new QuadraticModel1D());

		/* generate coefficient tiles for all patches
		 * TODO interpolate quadratic models */
		final HashMap<MinimalTileSpecWrapper, ArrayList<Tile<? extends M>>> coefficientsTiles =
				(HashMap) generateCoefficientsTiles(patches,
										   new InterpolatedQuadraticAffineModel1D<>(
												  new InterpolatedQuadraticAffineModel1D<>(
														  new QuadraticModel1D(),
														  new TranslationModel1D(),
														  lambda1 ),
												  new IdentityModel(),
												  lambda2 ),
										  numCoefficients * numCoefficients);

		/* completed patches */
		final HashSet<MinimalTileSpecWrapper> completedPatches = new HashSet<>();

		/* collect patch pairs */
		// find the images that actually overlap (only for those we can extract intensity PointMatches)
		final ArrayList<ValuePair<MinimalTileSpecWrapper, MinimalTileSpecWrapper>> patchPairs = new ArrayList<>();

		System.out.println("Collecting patch pairs ... ");

		for (final MinimalTileSpecWrapper p1 : patches) {
			completedPatches.add(p1);

			final RealInterval r1 = getBoundingBox(p1);

			final ArrayList<MinimalTileSpecWrapper> p2s = new ArrayList<>();

			for (final MinimalTileSpecWrapper p2 : patches) {
				final FinalRealInterval i = Intervals.intersect(r1, getBoundingBox(p2));

				if (i.realMax(0) - i.realMin(0) > 0 && i.realMax(1) - i.realMin(1) > 0) {
					// TODO: test in z, only if they are close enough in z connect them
					p2s.add(p2);
				}
			}

			for (final MinimalTileSpecWrapper p2 : p2s) {
				/*
				 * if this patch had been processed earlier, all matches are
				 * already in
				 */
				if (completedPatches.contains(p2))
					continue;

				patchPairs.add(new ValuePair<>(p1, p2));
				System.out.println(p1.getImageCol() + " <> " + p2.getImageCol());
			}
		}

		final int numThreads = Math.max(1, Threads.numThreads() / 2);
		final int meshResolution = 64; //?

		System.out.println("Matching intensities using " + numThreads + " threads ... ");

		// for all pairs of images that do overlap, extract matching intensity values (intensity values that should be the same)
		// TODO: parallelize on SPARK
		final ExecutorService exec = Executors.newFixedThreadPool(numThreads);
		final ArrayList<Future<?>> futures = new ArrayList<>();
		for (final ValuePair<MinimalTileSpecWrapper, MinimalTileSpecWrapper> patchPair : patchPairs) {
			futures.add(exec.submit(new Matcher(
									patchPair,
									(HashMap)coefficientsTiles,
									filter,
									scale,
									numCoefficients,
									meshResolution,
									imageProcessorCache)));
		}

		for (final Future<?> future : futures)
			future.get();

		/* connect tiles within patches */
		System.out.println("Connecting coefficient tiles in the same patch  ... ");

		for (final MinimalTileSpecWrapper p1 : completedPatches) {
			/* get the coefficient tiles */
			final ArrayList<Tile<? extends M>> p1CoefficientsTiles = coefficientsTiles.get(p1);

			for (int y = 1; y < numCoefficients; ++y) {
				final int yr = numCoefficients * y;
				final int yr1 = yr - numCoefficients;
				for (int x = 0; x < numCoefficients; ++x) {
					identityConnect(p1CoefficientsTiles.get(yr1 + x), p1CoefficientsTiles.get(yr + x), neighborWeight);
				}
			}
			for (int y = 0; y < numCoefficients; ++y) {
				final int yr = numCoefficients * y;
				for (int x = 1; x < numCoefficients; ++x) {
					final int yrx = yr + x;
					identityConnect(p1CoefficientsTiles.get(yrx), p1CoefficientsTiles.get(yrx - 1), neighborWeight);
				}
			}
		}

		/* optimize */
		System.out.println("Optimizing ... ");
		final TileConfiguration tc = new TileConfiguration();
		for (final ArrayList<Tile<? extends M>> coefficients : coefficientsTiles.values()) {
			tc.addTiles(coefficients);
		}

		try {
			TileUtil.optimizeConcurrently(new ErrorStatistic(iterations + 1), 0.01f, iterations, iterations, 0.75f,
					tc, tc.getTiles(), tc.getFixedTiles(), numThreads);
		}
		catch (final Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		// TODO: this code should be computed on-the-fly as a function of the coefficients
		final ArrayList<OnTheFlyIntensityQuadratic> correctedOnTheFly = new ArrayList<>();

		for (final MinimalTileSpecWrapper p : patches) {
			/* save coefficients */
			final double[][] abc_coefficients = new double[numCoefficients * numCoefficients][3];

			final ArrayList<Tile<? extends M>> tiles = coefficientsTiles.get(p);

			for (int i = 0; i < numCoefficients * numCoefficients; ++i) {
				final Quadratic1D<?> model = tiles.get(i).getModel();
				model.toArray(abc_coefficients[i]);
			}

			correctedOnTheFly.add(new OnTheFlyIntensityQuadratic(p, abc_coefficients, numCoefficients));
		}

		return correctedOnTheFly;
	}

	static protected void identityConnect(final Tile<?> t1, final Tile<?> t2, final double weight) {
		final ArrayList<PointMatch> matches = new ArrayList<>();
		matches.add(new PointMatch(new Point(new double[]{0}), new Point(new double[]{0})));
		matches.add(new PointMatch(new Point(new double[]{1}), new Point(new double[]{1})));
		t1.connect(t2, matches);
	}

	public static RealInterval getBoundingBox(final MinimalTileSpecWrapper m) {
		final double[] p1min = new double[]{ m.getTileSpec().getMinX(), m.getTileSpec().getMinY() };
		final double[] p1max = new double[]{ m.getTileSpec().getMaxX(), m.getTileSpec().getMaxY() };

		return new FinalRealInterval(p1min, p1max);
	}

	protected <T extends Model<T> & Quadratic1D<T>> HashMap<MinimalTileSpecWrapper, ArrayList<Tile<T>>> generateCoefficientsTiles(
			final Collection<MinimalTileSpecWrapper> patches,
			final T template,
			final int nCoefficients) {

		final HashMap<MinimalTileSpecWrapper, ArrayList<Tile<T>>> map = new HashMap<>();
		for (final MinimalTileSpecWrapper p : patches) {
			final ArrayList<Tile<T>> coefficientModels = new ArrayList<>();
			for (int i = 0; i < nCoefficients; ++i)
				coefficientModels.add(new Tile<>(template.copy()));

			map.put(p, coefficientModels);
		}
		return map;
	}
}

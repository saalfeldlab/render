package org.janelia.render.client.newsolver.solvers.intensity;

import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import mpicbg.models.Affine1D;
import mpicbg.models.AffineModel1D;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.IdentityModel;
import mpicbg.models.InterpolatedAffineModel1D;
import mpicbg.models.NoninvertibleModelException;
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
import net.imglib2.util.Pair;
import net.imglib2.util.StopWatch;
import net.imglib2.util.ValuePair;

import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.intensityadjust.AdjustBlock;
import org.janelia.render.client.intensityadjust.intensity.PointMatchFilter;
import org.janelia.render.client.intensityadjust.intensity.RansacRegressionReduceFilter;
import org.janelia.render.client.intensityadjust.intensity.Render;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.assembly.ResultContainer;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMIntensityCorrectionParameters;
import org.janelia.render.client.newsolver.solvers.Worker;
import org.janelia.render.client.parameter.ZDistanceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class AffineIntensityCorrectionBlockWorker<M>
		extends Worker<ArrayList<AffineModel1D>, FIBSEMIntensityCorrectionParameters<M>> {

	private final FIBSEMIntensityCorrectionParameters<M> parameters;
	private static final int ITERATIONS = 2000;

	public AffineIntensityCorrectionBlockWorker(
			final BlockData<ArrayList<AffineModel1D>, FIBSEMIntensityCorrectionParameters<M>> blockData,
			final int numThreads) throws IOException {

		super(blockData, numThreads);
		parameters = blockData.solveTypeParameters();
	}

	/**
	 * runs the Worker
	 */
	@Override
	public List<BlockData<ArrayList<AffineModel1D>, FIBSEMIntensityCorrectionParameters<M>>> call()
			throws IOException, ExecutionException, InterruptedException, NoninvertibleModelException {

		LOG.info("call: entry, blockData={}", blockData);
		fetchResolvedTiles();
		final List<TileSpec> wrappedTiles = AdjustBlock.sortTileSpecs(blockData.rtsc());

		final HashMap<String, ArrayList<Tile<? extends Affine1D<?>>>> coefficientTiles = computeCoefficients(wrappedTiles);

		if (coefficientTiles == null)
			throw new RuntimeException("no coefficient tiles were computed for block " + blockData);

		coefficientTiles.forEach((tileId, tiles) -> {
			final ArrayList<AffineModel1D> models = new ArrayList<>();
			tiles.forEach(tile -> {
				final AffineModel1D model = ((InterpolatedAffineModel1D<?, ?>) tile.getModel()).createAffineModel1D();
				models.add(model);
			});
			blockData.getResults().recordModel(tileId, models);
		});

		LOG.info("call: exit, blockData={}", blockData);
		return new ArrayList<>(List.of(blockData));
	}

	private void fetchResolvedTiles() throws IOException {
		final Bounds bounds = blockData.getOriginalBounds();
		final ResolvedTileSpecCollection rtsc = renderDataClient.getResolvedTiles(
				parameters.stack(),
				bounds.getMinZ(), bounds.getMaxZ(),
				null, // groupId,
				bounds.getMinX(), bounds.getMaxX(),
				bounds.getMinY(), bounds.getMaxY(),
				null); // matchPattern
		blockData.getResults().init(rtsc);
	}

	private HashMap<String, ArrayList<Tile<? extends Affine1D<?>>>> computeCoefficients(final List<TileSpec> tiles) throws ExecutionException, InterruptedException {

		LOG.info("deriveIntensityFilterData: entry");
		if (tiles.size() < 2) {
			final String tileCountMsg = tiles.size() == 1 ? "1 tile" : "0 tiles";
			LOG.info("deriveIntensityFilterData: skipping correction because collection contains {}", tileCountMsg);
			return null;
		}

		final long maxCachedPixels = parameters.maxNumberOfCachedPixels();
		final ImageProcessorCache imageProcessorCache = (maxCachedPixels == 0)
				? ImageProcessorCache.DISABLED_CACHE
				: new ImageProcessorCache(parameters.maxNumberOfCachedPixels(), true, false);

		final HashMap<String, ArrayList<Tile<? extends Affine1D<?>>>> coefficientTiles = splitIntoCoefficientTiles(tiles, imageProcessorCache);

		solveForGlobalCoefficients(coefficientTiles, ITERATIONS);

		return coefficientTiles;
	}

	private HashMap<String, ArrayList<Tile<? extends Affine1D<?>>>> splitIntoCoefficientTiles(
			final List<TileSpec> tiles,
			final ImageProcessorCache imageProcessorCache) throws InterruptedException, ExecutionException {

		LOG.info("splitIntoCoefficientTiles: entry, collecting pairs for {} patches with zDistance {}", tiles.size(), parameters.zDistance());

		// generate coefficient tiles for all patches
		final int nGridPoints = parameters.numCoefficients() * parameters.numCoefficients();
		final HashMap<String, ArrayList<Tile<? extends Affine1D<?>>>> coefficientTiles = generateCoefficientsTiles(tiles, nGridPoints);

		final ArrayList<ValuePair<TileSpec, TileSpec>> patchPairs = findOverlappingPatches(tiles, parameters.zDistance());

		LOG.info("splitIntoCoefficientTiles: found {} pairs for {} patches with zDistance {} -- matching intensities with {} threads", patchPairs.size(), tiles.size(), parameters.zDistance(), numThreads);

		// for all pairs of images that do overlap, extract matching intensity values (intensity values that should be the same)
		final ExecutorService exec = Executors.newFixedThreadPool(numThreads);
		final PointMatchFilter filter = new RansacRegressionReduceFilter(new AffineModel1D());
		final ArrayList<Future<?>> matchComputations = new ArrayList<>();
		final int meshResolution = tiles.isEmpty() ? 64 : (int) tiles.get(0).getMeshCellSize();
		for (final ValuePair<TileSpec, TileSpec> patchPair : patchPairs) {
			final Matcher matchJob = new Matcher(patchPair,
												 coefficientTiles,
												 filter,
												 parameters.renderScale(),
												 parameters.numCoefficients(),
												 meshResolution,
												 imageProcessorCache);
			matchComputations.add(exec.submit(matchJob));
		}

		for (final Future<?> future : matchComputations)
			future.get();

		final List<Future<Pair<String, ArrayList<Double>>>> averageComputations = new ArrayList<>();
		for (final TileSpec tile : tiles) {
			averageComputations.add(exec.submit(() -> computeAverages(tile, parameters.numCoefficients(), parameters.renderScale(), meshResolution, imageProcessorCache)));
			blockData.getResults().recordMatchedTile(tile.getIntegerZ(), tile.getTileId());
		}

		final ResultContainer<ArrayList<AffineModel1D>> results = blockData.getResults();
		for (final Future<Pair<String, ArrayList<Double>>> average : averageComputations) {
			results.recordAverages(average.get().getA(),
								   average.get().getB());
		}

		exec.shutdown();
		LOG.info("splitIntoCoefficientTiles: after matching, imageProcessorCache stats are: {}", imageProcessorCache.getStats());
		return coefficientTiles;
	}

	private  HashMap<String, ArrayList<Tile<? extends Affine1D<?>>>> generateCoefficientsTiles(
			final Collection<TileSpec> patches,
			final int nGridPoints) {

		final InterpolatedAffineModel1D<InterpolatedAffineModel1D<AffineModel1D, TranslationModel1D>, IdentityModel> modelTemplate =
				new InterpolatedAffineModel1D<>(
						new InterpolatedAffineModel1D<>(
								new AffineModel1D(), new TranslationModel1D(), parameters.lambdaTranslation()),
						new IdentityModel(), parameters.lambdaIdentity());

		final HashMap<String, ArrayList<Tile<? extends Affine1D<?>>>> coefficientTiles = new HashMap<>();
		for (final TileSpec p : patches) {
			final ArrayList<Tile<? extends Affine1D<?>>> coefficientModels = new ArrayList<>();
			for (int i = 0; i < nGridPoints; ++i) {
				final InterpolatedAffineModel1D<?,?> model = modelTemplate.copy();
				coefficientModels.add(new Tile<>(model));
			}
			coefficientTiles.put(p.getTileId(), coefficientModels);
		}
		return coefficientTiles;
	}

	private static ArrayList<ValuePair<TileSpec, TileSpec>> findOverlappingPatches(
			final List<TileSpec> allPatches,
			final ZDistanceParameters zDistance) {
		// find the images that actually overlap (only for those we can extract intensity PointMatches)
		final ArrayList<ValuePair<TileSpec, TileSpec>> patchPairs = new ArrayList<>();
		final Set<TileSpec> unconsideredPatches = new HashSet<>(allPatches);

		for (final TileSpec p1 : allPatches) {
			unconsideredPatches.remove(p1);
			final RealInterval r1 = getBoundingBox(p1);
			final TileBounds p1Bounds = p1.toTileBounds();

			for (final TileSpec p2 : unconsideredPatches) {
				final FinalRealInterval i = Intervals.intersect(r1, getBoundingBox(p2));
				final TileBounds p2Bounds = p2.toTileBounds();

				final double deltaX = i.realMax(0) - i.realMin(0);
				final double deltaY = i.realMax(1) - i.realMin(1);
				if ((deltaX > 0) && (deltaY > 0) && zDistance.includePair(p1Bounds, p2Bounds))
					patchPairs.add(new ValuePair<>(p1, p2));
			}
		}
		return patchPairs;
	}

	private static RealInterval getBoundingBox(final TileSpec m) {
		final double[] p1min = new double[]{ m.getMinX(), m.getMinY() };
		final double[] p1max = new double[]{ m.getMaxX(), m.getMaxY() };
		return new FinalRealInterval(p1min, p1max);
	}

	@SuppressWarnings("SameParameterValue")
	private void solveForGlobalCoefficients(final HashMap<String, ArrayList<Tile<? extends Affine1D<?>>>> coefficientTiles,
											final int iterations) {

		final Tile<? extends Affine1D<?>> equilibrationTile = new Tile<>(new IdentityModel());

		connectTilesWithinPatches(coefficientTiles, equilibrationTile);

		/* optimize */
		final TileConfiguration tc = new TileConfiguration();
		coefficientTiles.values().forEach(tc::addTiles);

		// anchor the equilibration tile
		tc.addTile(equilibrationTile);
		tc.fixTile(equilibrationTile);

		LOG.info("solveForGlobalCoefficients: optimizing {} tiles with {} threads", tc.getTiles().size(), numThreads);
		try {
			TileUtil.optimizeConcurrently(new ErrorStatistic(iterations + 1), 0.01f, iterations, iterations, 0.75f, tc, tc.getTiles(), tc.getFixedTiles(), 1);
		} catch (final Exception e) {
			throw new RuntimeException(e);
		}

		// TODO: this is not the right error measure, what is idToBlockErrorMap supposed to be exactly?
		coefficientTiles.forEach((tileId, tiles) -> {
			final Double error = tiles.stream().mapToDouble(t -> {
				t.updateCost();
				return t.getDistance();
			}).average().orElse(Double.MAX_VALUE);
			final Map<String, Double> errorMap = new HashMap<>();
			errorMap.put(tileId, error);
			blockData.getResults().recordAllErrors(tileId, errorMap);
		});

		LOG.info("solveForGlobalCoefficients: exit, returning intensity coefficients for {} tiles", coefficientTiles.size());
	}

	private void connectTilesWithinPatches(final HashMap<String, ArrayList<Tile<? extends Affine1D<?>>>> coefficientTiles,
										   final Tile<? extends Affine1D<?>> equilibrationTile) {
		final Collection<TileSpec> allTiles = blockData.rtsc().getTileSpecs();
		final double equilibrationWeight = blockData.solveTypeParameters().equilibrationWeight();

		final ResultContainer<ArrayList<AffineModel1D>> results = blockData.getResults();
		for (final TileSpec p : allTiles) {
			final List<? extends Tile<?>> coefficientTile = coefficientTiles.get(p.getTileId());
			for (int i = 1; i < parameters.numCoefficients(); ++i) {
				for (int j = 0; j < parameters.numCoefficients(); ++j) {
					final int left = getLinearIndex(i-1, j, parameters.numCoefficients());
					final int right = getLinearIndex(i, j, parameters.numCoefficients());
					final int top = getLinearIndex(j, i, parameters.numCoefficients());
					final int bot = getLinearIndex(j, i-1, parameters.numCoefficients());

					identityConnect(coefficientTile.get(right), coefficientTile.get(left));
					identityConnect(coefficientTile.get(top), coefficientTile.get(bot));
				}
			}
			if (equilibrationWeight > 0.0) {
				final List<Double> averages = results.getAveragesFor(p.getTileId());
				for (int i = 0; i < parameters.numCoefficients(); i++) {
					for (int j = 0; j < parameters.numCoefficients(); j++) {
						final int idx = getLinearIndex(i, j, parameters.numCoefficients());
						equilibrateIntensity(coefficientTile.get(idx),
											 equilibrationTile,
											 averages.get(idx),
											 equilibrationWeight);
					}
				}
			}
		}
	}

	/**
	 * Get index of the (x,y) pixel in an n x n grid represented by a linear array
	 */
	private int getLinearIndex(final int x, final int y, final int n) {
		return y * n + x;
	}

	private static void equilibrateIntensity(final Tile<?> coefficientTile,
											 final Tile<?> equilibrationTile,
											 final Double average,
											 final double weight) {
		final PointMatch eqMatch = new PointMatch(new Point(new double[] { average }),
												  new Point(new double[] { 0.5 }),
												  weight);
		coefficientTile.connect(equilibrationTile, List.of(eqMatch));
	}

	static protected void identityConnect(final Tile<?> t1, final Tile<?> t2) {
		final ArrayList<PointMatch> matches = new ArrayList<>();
		matches.add(new PointMatch(new Point(new double[] { 0 }), new Point(new double[] { 0 })));
		matches.add(new PointMatch(new Point(new double[] { 1 }), new Point(new double[] { 1 })));
		t1.connect(t2, matches);
	}

	private Pair<String, ArrayList<Double>> computeAverages(
			final TileSpec tile,
			final int numCoefficients,
			final double scale,
			final int meshResolution,
			final ImageProcessorCache imageProcessorCache) {

		final Interval interval = Intervals.smallestContainingInterval(getBoundingBox(tile));
		final Rectangle box = new Rectangle((int)interval.min(0), (int)interval.min(1), (int)interval.dimension(0), (int)interval.dimension(1));

		final int w = (int) (box.width * parameters.renderScale() + 0.5);
		final int h = (int) (box.height * parameters.renderScale() + 0.5);
		final int n = w * h;

		final FloatProcessor pixels = new FloatProcessor(w, h);
		final FloatProcessor weights = new FloatProcessor(w, h);
		final ColorProcessor subTiles = new ColorProcessor(w, h);

		Render.render(tile, numCoefficients, numCoefficients, pixels, weights, subTiles, box.x, box.y, scale, meshResolution, imageProcessorCache);

		final float[] averages = new float[numCoefficients * numCoefficients];
		final int[] counts = new int[numCoefficients * numCoefficients];

		// iterate over all pixels to compute averages
		for (int i = 0; i < n; ++i) {
			final int label = subTiles.get(i);

			/* first label is 1 */
			if (label > 0) {
				final float p = pixels.getf(i);
				averages[label - 1] += p;
				counts[label - 1]++;
			}
		}

		final ArrayList<Double> result = new ArrayList<>();
		for (int i = 0; i < averages.length; ++i)
			result.add((double) (averages[i] / counts[i]));

		return new ValuePair<>(tile.getTileId(), result);
	}

	private static final Logger LOG = LoggerFactory.getLogger(AffineIntensityCorrectionBlockWorker.class);

	static final private class Matcher implements Runnable
	{
		//final private Rectangle roi;
		final private ValuePair<TileSpec, TileSpec> patchPair;
		final private HashMap<String, ArrayList<Tile<? extends Affine1D<?>>>> coefficientTiles;
		final private PointMatchFilter filter;
		final private double scale;
		final private int numCoefficients;
		final int meshResolution;
		final ImageProcessorCache imageProcessorCache;

		public Matcher(
				final ValuePair<TileSpec, TileSpec> patchPair,
				final HashMap<String, ArrayList<Tile<? extends Affine1D<?>>>> coefficientTiles,
				final PointMatchFilter filter,
				final double scale,
				final int numCoefficients,
				final int meshResolution,
				final ImageProcessorCache imageProcessorCache)
		{
			this.patchPair = patchPair;
			this.coefficientTiles = coefficientTiles;
			this.filter = filter;
			this.scale = scale;
			this.numCoefficients = numCoefficients;
			this.meshResolution = meshResolution;
			this.imageProcessorCache = imageProcessorCache;
		}

		@Override
		public void run()
		{
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
					final PointMatch pq = new PointMatch(new Point(new double[] {p}), new Point(new double[] {q}), weight1 * weight2);

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
			final Interval i1 = Intervals.smallestContainingInterval(getBoundingBox(p1));
			final Rectangle box1 = new Rectangle((int)i1.min(0), (int)i1.min(1), (int)i1.dimension(0), (int)i1.dimension(1));
			final Interval i2 = Intervals.smallestContainingInterval(getBoundingBox(p2));
			final Rectangle box2 = new Rectangle((int)i2.min(0), (int)i2.min(1), (int)i2.dimension(0), (int)i2.dimension(1));
			return box1.intersection(box2);
		}
	}
}

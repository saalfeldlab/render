package org.janelia.render.client.newsolver.solvers.intensity;

import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import mpicbg.models.Affine1D;
import mpicbg.models.AffineModel1D;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.IdentityModel;
import mpicbg.models.InterpolatedAffineModel1D;
import mpicbg.models.Model;
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
import org.janelia.alignment.filter.FilterSpec;
import org.janelia.alignment.filter.IntensityMap8BitFilter;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.intensityadjust.AdjustBlock;
import org.janelia.render.client.intensityadjust.MinimalTileSpecWrapper;
import org.janelia.render.client.intensityadjust.intensity.PointMatchFilter;
import org.janelia.render.client.intensityadjust.intensity.RansacRegressionReduceFilter;
import org.janelia.render.client.intensityadjust.intensity.Render;
import org.janelia.render.client.intensityadjust.virtual.LinearOnTheFlyIntensity;
import org.janelia.render.client.intensityadjust.virtual.OnTheFlyIntensity;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMIntensityCorrectionParameters;
import org.janelia.render.client.newsolver.solvers.Worker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class AffineIntensityCorrectionBlockWorker<M, F extends BlockFactory<F>>
		extends Worker<M, ArrayList<AffineModel1D>, FIBSEMIntensityCorrectionParameters<M>, F> {

	private final FIBSEMIntensityCorrectionParameters<M> parameters;

	public AffineIntensityCorrectionBlockWorker(
			final BlockData<M, ArrayList<AffineModel1D>, FIBSEMIntensityCorrectionParameters<M>, F> blockData,
			final int startId,
			final int numThreads) throws IOException {

		super(startId, blockData, numThreads);
		parameters = blockData.solveTypeParameters();
	}

	/**
	 * runs the Worker
	 */
	@Override
	public void run() throws IOException, ExecutionException, InterruptedException, NoninvertibleModelException {
		final List<MinimalTileSpecWrapper> wrappedTiles = AdjustBlock.wrapTileSpecs(blockData.rtsc());

		for (final MinimalTileSpecWrapper wrappedTile : wrappedTiles) {
			final String tileId = wrappedTile.getTileId();
			final int z = (int) Math.round(wrappedTile.getZ());
			blockData.zToTileId().computeIfAbsent(z, k -> new HashSet<>()).add(tileId);
		}

		final HashMap<MinimalTileSpecWrapper, ArrayList<Tile<? extends Affine1D<?>>>> coefficientTiles = computeCoefficients(wrappedTiles);

		// TODO: this should happen in Assembler after merging the blocks
		// this adds the filters to the tile specs and pushes the data to the DB
		final boolean saveResults = true;
		if (saveResults) {
			final Map<String, FilterSpec> idToFilterSpec = convertCoefficientsToFilter(wrappedTiles, coefficientTiles);
			addFilters(blockData.rtsc(), idToFilterSpec);
			renderDataClient.saveResolvedTiles(blockData.rtsc(), "v5_acquire_trimmed_test_intensity", null);
		}

		LOG.info("AffineIntensityCorrectionBlockWorker: exit, minZ={}, maxZ={}", blockData.minZ(), blockData.maxZ());
	}

	private HashMap<MinimalTileSpecWrapper, ArrayList<Tile<? extends Affine1D<?>>>> computeCoefficients(final List<MinimalTileSpecWrapper> tiles) throws ExecutionException, InterruptedException {

		LOG.info("deriveIntensityFilterData: entry");
		if (tiles.size() <2) {
			final String tileCountMsg = tiles.size() == 1 ? "1 tile" : "0 tiles";
			LOG.info("deriveIntensityFilterData: skipping correction because collection contains {}", tileCountMsg);
			return null;
		}

		final long maxCachedPixels = parameters.maxNumberOfCachedPixels();
		final ImageProcessorCache imageProcessorCache = (maxCachedPixels == 0)
				? ImageProcessorCache.DISABLED_CACHE
				: new ImageProcessorCache(parameters.maxNumberOfCachedPixels(), true, false);

		final HashMap<MinimalTileSpecWrapper, ArrayList<Tile<? extends Affine1D<?>>>> coefficientTiles = splitIntoCoefficientTiles(tiles, imageProcessorCache);
		
		final int iterations = 2000;
		solveForGlobalCoefficients(coefficientTiles, iterations);

		return coefficientTiles;
	}

	private HashMap<MinimalTileSpecWrapper, ArrayList<Tile<? extends Affine1D<?>>>> splitIntoCoefficientTiles(
			final List<MinimalTileSpecWrapper> tiles,
			final ImageProcessorCache imageProcessorCache) throws InterruptedException, ExecutionException {

		LOG.info("splitIntoCoefficientTiles: entry, collecting pairs for {} patches with zDistance {}", tiles.size(), parameters.zDistance());

		// generate coefficient tiles for all patches
		final int nGridPoints = parameters.numCoefficients() * parameters.numCoefficients();
		@SuppressWarnings("unchecked")
		final HashMap<MinimalTileSpecWrapper, ArrayList<Tile<? extends Affine1D<?>>>> coefficientTiles =
				(HashMap) generateCoefficientsTiles(tiles, nGridPoints);

		final ArrayList<ValuePair<MinimalTileSpecWrapper, MinimalTileSpecWrapper>> patchPairs = findOverlappingPatches(tiles, parameters.zDistance());

		LOG.info("splitIntoCoefficientTiles: found {} pairs for {} patches with zDistance {} -- matching intensities with {} threads", patchPairs.size(), tiles.size(), parameters.zDistance(), numThreads);

		// for all pairs of images that do overlap, extract matching intensity values (intensity values that should be the same)
		final ExecutorService exec = Executors.newFixedThreadPool(numThreads);
		final PointMatchFilter filter = new RansacRegressionReduceFilter(new AffineModel1D());
		final ArrayList<Future<?>> matchComputations = new ArrayList<>();
		final int meshResolution = tiles.isEmpty() ? 64 : (int) tiles.get(0).getTileSpec().getMeshCellSize();
		for (final ValuePair<MinimalTileSpecWrapper, MinimalTileSpecWrapper> patchPair : patchPairs) {
			final Matcher matchJob = new Matcher(patchPair,
												 (HashMap) coefficientTiles,
												 filter,
												 parameters.renderScale(),
												 parameters.numCoefficients(),
												 meshResolution,
												 imageProcessorCache);
			matchComputations.add(exec.submit(matchJob));
		}

		for (final Future<?> future : matchComputations)
			future.get();

		final List<Future<Pair<String, ArrayList<Double>>>> averageCompuations = new ArrayList<>();
		for (final MinimalTileSpecWrapper tile : tiles)
			averageCompuations.add(exec.submit(() -> computeAverages(tile, parameters.numCoefficients(), parameters.renderScale(), meshResolution, imageProcessorCache)));

		for (final Future<Pair<String, ArrayList<Double>>> average : averageCompuations)
			blockData.idToAverages().put(average.get().getA(), average.get().getB());

		exec.shutdown();
		LOG.info("splitIntoCoefficientTiles: after matching, imageProcessorCache stats are: {}", imageProcessorCache.getStats());
		return coefficientTiles;
	}

	private  <T extends Model<T> & Affine1D<T>> HashMap<MinimalTileSpecWrapper, ArrayList<Tile<T>>> generateCoefficientsTiles(
			final Collection<MinimalTileSpecWrapper> patches,
			final int nGridPoints) {

		final InterpolatedAffineModel1D<InterpolatedAffineModel1D<AffineModel1D, TranslationModel1D>, IdentityModel> modelTemplate =
				new InterpolatedAffineModel1D<>(
						new InterpolatedAffineModel1D<>(
								new AffineModel1D(), new TranslationModel1D(), parameters.lambdaTranslation()),
						new IdentityModel(), parameters.lambdaIdentity());

		final HashMap<MinimalTileSpecWrapper, ArrayList<Tile<T>>> coefficientTiles = new HashMap<>();
		for (final MinimalTileSpecWrapper p : patches) {
			final ArrayList<Tile<T>> coefficientModels = new ArrayList<>();
			for (int i = 0; i < nGridPoints; ++i) {
				@SuppressWarnings("unchecked")
				final T model = (T) modelTemplate.copy();
				coefficientModels.add(new Tile<>(model));
			}
			coefficientTiles.put(p, coefficientModels);
		}
		return coefficientTiles;
	}

	private static ArrayList<ValuePair<MinimalTileSpecWrapper, MinimalTileSpecWrapper>> findOverlappingPatches(
			final List<MinimalTileSpecWrapper> allPatches,
			final Integer zDistance) {
		// find the images that actually overlap (only for those we can extract intensity PointMatches)
		final ArrayList<ValuePair<MinimalTileSpecWrapper, MinimalTileSpecWrapper>> patchPairs = new ArrayList<>();
		final Set<MinimalTileSpecWrapper> unconsideredPatches = new HashSet<>(allPatches);

		final double maxDeltaZ = (zDistance == null) ? Double.MAX_VALUE : zDistance;

		for (final MinimalTileSpecWrapper p1 : allPatches) {
			unconsideredPatches.remove(p1);
			final RealInterval r1 = getBoundingBox(p1);

			for (final MinimalTileSpecWrapper p2 : unconsideredPatches) {
				final FinalRealInterval i = Intervals.intersect(r1, getBoundingBox(p2));

				final double deltaX = i.realMax(0) - i.realMin(0);
				final double deltaY = i.realMax(1) - i.realMin(1);
				final double deltaZ = Math.abs(p1.getZ() - p2.getZ());
				if ((deltaX > 0) && (deltaY > 0) && (deltaZ < maxDeltaZ))
					patchPairs.add(new ValuePair<>(p1, p2));
			}
		}
		return patchPairs;
	}

	private static RealInterval getBoundingBox(final MinimalTileSpecWrapper m) {
		final double[] p1min = new double[]{ m.getTileSpec().getMinX(), m.getTileSpec().getMinY() };
		final double[] p1max = new double[]{ m.getTileSpec().getMaxX(), m.getTileSpec().getMaxY() };
		return new FinalRealInterval(p1min, p1max);
	}

	private void solveForGlobalCoefficients(final HashMap<MinimalTileSpecWrapper, ArrayList<Tile<? extends Affine1D<?>>>> coefficientTiles, final int iterations) {
		connectTilesWithinPatches(coefficientTiles);

		/* optimize */
		final TileConfiguration tc = new TileConfiguration();
		coefficientTiles.values().forEach(tc::addTiles);

		LOG.info("solveForGlobalCoefficients: optimizing {} tiles with {} threads", tc.getTiles().size(), numThreads);
		try {
			TileUtil.optimizeConcurrently(new ErrorStatistic(iterations + 1), 0.01f, iterations, iterations, 0.75f, tc, tc.getTiles(), tc.getFixedTiles(), 1);
		} catch (final Exception e) {
			throw new RuntimeException(e);
		}

		// TODO: this is not the right error measure, what is idToBlockErrorMap supposed to be exactly?
		for (final Map.Entry<MinimalTileSpecWrapper, ArrayList<Tile<? extends Affine1D<?>>>> entry : coefficientTiles.entrySet()) {
			final String tileId = entry.getKey().getTileId();
			final Double error = entry.getValue().stream().mapToDouble(t -> t.getModel().getCost()).average().orElse(Double.MAX_VALUE);
			final List<Pair<String, Double>> errorList = new ArrayList<>();
			errorList.add(new ValuePair<>(tileId, error));
			blockData.idToBlockErrorMap().put(tileId, errorList);
		}

		LOG.info("solveForGlobalCoefficients: exit, returning intensity coefficients for {} tiles", coefficientTiles.size());
	}

	private void connectTilesWithinPatches(final HashMap<MinimalTileSpecWrapper, ArrayList<Tile<? extends Affine1D<?>>>> coefficientTiles) {
		final HashSet<MinimalTileSpecWrapper> allTiles = new HashSet<>(coefficientTiles.keySet());

		for (final MinimalTileSpecWrapper p : allTiles) {
			final ArrayList<? extends Tile<?>> coefficientTile = coefficientTiles.get(p);
			for (int i = 1; i < parameters.numCoefficients(); ++i) {
				for (int j = 0; j < parameters.numCoefficients(); ++j) {
					// connect left to right
					final int left = getLinearIndex(i-1, j, parameters.numCoefficients());
					final int right = getLinearIndex(i, j, parameters.numCoefficients());
					identityConnect(coefficientTile.get(right), coefficientTile.get(left));

					// connect top to bottom
					final int top = getLinearIndex(j, i, parameters.numCoefficients());
					final int bot = getLinearIndex(j, i-1, parameters.numCoefficients());
					identityConnect(coefficientTile.get(top), coefficientTile.get(bot));
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

	static protected void identityConnect(final Tile<?> t1, final Tile<?> t2) {
		final ArrayList<PointMatch> matches = new ArrayList<>();
		matches.add(new PointMatch(new Point(new double[] { 0 }), new Point(new double[] { 0 })));
		matches.add(new PointMatch(new Point(new double[] { 1 }), new Point(new double[] { 1 })));
		t1.connect(t2, matches);
	}

	private Map<String, FilterSpec> convertCoefficientsToFilter(final List<MinimalTileSpecWrapper> tiles, final HashMap<MinimalTileSpecWrapper, ArrayList<Tile<? extends Affine1D<?>>>> coefficientTiles) {
		final ArrayList<OnTheFlyIntensity> corrected = convertModelsToOtfIntensities(tiles, parameters.numCoefficients(), coefficientTiles);

		final Map<String, FilterSpec> idToFilterSpec = new HashMap<>();
		for (final OnTheFlyIntensity onTheFlyIntensity : corrected) {
			final String tileId = onTheFlyIntensity.getMinimalTileSpecWrapper().getTileId();
			final IntensityMap8BitFilter filter = onTheFlyIntensity.toFilter();
			final FilterSpec filterSpec = new FilterSpec(filter.getClass().getName(), filter.toParametersMap());
			idToFilterSpec.put(tileId, filterSpec);
		}
		return idToFilterSpec;
	}

	private void addFilters(final ResolvedTileSpecCollection tileSpecs, final Map<String, FilterSpec> idToFilterSpec) {
		for (final Map.Entry<String, FilterSpec> entry : idToFilterSpec.entrySet()) {
			final String tileId = entry.getKey();
			final FilterSpec filterSpec = entry.getValue();
			final TileSpec tileSpec = tileSpecs.getTileSpec(tileId);
			tileSpec.setFilterSpec(filterSpec);
			tileSpec.convertSingleChannelSpecToLegacyForm();
		}
	}

	private ArrayList<OnTheFlyIntensity> convertModelsToOtfIntensities(
			final List<MinimalTileSpecWrapper> patches,
			final int numCoefficients,
			final Map<MinimalTileSpecWrapper, ArrayList<Tile<? extends Affine1D<?>>>> coefficientTiles) {

		final ArrayList<OnTheFlyIntensity> correctedOnTheFly = new ArrayList<>();
		for (final MinimalTileSpecWrapper p : patches) {
			/* save coefficients */
			final double[][] ab_coefficients = new double[numCoefficients * numCoefficients][2];

			final ArrayList<Tile<? extends Affine1D<?>>> tiles = coefficientTiles.get(p);

			for (int i = 0; i < numCoefficients * numCoefficients; ++i)
			{
				final Tile<? extends Affine1D<?>> t = tiles.get(i);
				final Affine1D<?> affine = t.getModel();
				affine.toArray(ab_coefficients[i]);
			}

			correctedOnTheFly.add(new LinearOnTheFlyIntensity(p, ab_coefficients, numCoefficients ));
		}
		return correctedOnTheFly;
	}

	private Pair<String, ArrayList<Double>> computeAverages(
			final MinimalTileSpecWrapper tile,
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

	/**
	 * @return - the result(s) of the solve, multiple ones if they were not connected
	 */
	@Override
	public ArrayList<BlockData<M, ArrayList<AffineModel1D>, FIBSEMIntensityCorrectionParameters<M>, F>> getBlockDataList() {
		return new ArrayList<>(List.of(blockData));
	}

	private static final Logger LOG = LoggerFactory.getLogger(Worker.class);

	static final private class Matcher implements Runnable
	{
		//final private Rectangle roi;
		final private ValuePair<MinimalTileSpecWrapper, MinimalTileSpecWrapper> patchPair;
		final private HashMap<MinimalTileSpecWrapper, ArrayList<Tile<?>>> coefficientTiles;
		final private PointMatchFilter filter;
		final private double scale;
		final private int numCoefficients;
		final int meshResolution;
		final ImageProcessorCache imageProcessorCache;

		public Matcher(
				final ValuePair<MinimalTileSpecWrapper, MinimalTileSpecWrapper> patchPair,
				final HashMap<MinimalTileSpecWrapper, ArrayList<Tile<?>>> coefficientTiles,
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
			final MinimalTileSpecWrapper p1 = patchPair.getA();
			final MinimalTileSpecWrapper p2 = patchPair.getB();

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
			int label1 = 0, label2 = 0, weight1 = 0, weight2 = 0;
			for (int i = 0; i < n; ++i) {
				// lazily check if it pays to create a match
				final boolean matchCanContribute = (label1 = subTiles1.get(i)) > 0
						&& (label2 = subTiles2.get(i)) > 0
						&& (weight1 = weights1.get(i)) > 0
						&& (weight2 = weights2.get(i)) > 0;

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
			final ArrayList<Tile<?>> p1CoefficientsTiles = coefficientTiles.get(p1);
			final ArrayList<Tile<?>> p2CoefficientsTiles = coefficientTiles.get(p2);
			int connectionCount = 0;

			for (int i = 0; i < dimSize; ++i) {
				final Tile<?> t1 = p1CoefficientsTiles.get(i);
				ra.setPosition(i, 0);

				for (int j = 0; j < dimSize; ++j) {
					ra.setPosition(j, 1);
					final ArrayList<PointMatch> matches = ra.get();
					if (matches.isEmpty())
						continue;

					final Tile<?> t2 = p2CoefficientsTiles.get(j);{
						t1.connect(t2, ra.get());
						connectionCount++;
					}
				}
			}

			stopWatch.stop();
			LOG.info("run: exit, pair {} <-> {} has {} connections, matching took {}", p1.getTileId(), p2.getTileId(), connectionCount, stopWatch);
		}

		private static Rectangle computeIntersection(final MinimalTileSpecWrapper p1, final MinimalTileSpecWrapper p2) {
			final Interval i1 = Intervals.smallestContainingInterval(getBoundingBox(p1));
			final Rectangle box1 = new Rectangle((int)i1.min(0), (int)i1.min(1), (int)i1.dimension(0), (int)i1.dimension(1));
			final Interval i2 = Intervals.smallestContainingInterval(getBoundingBox(p2));
			final Rectangle box2 = new Rectangle((int)i2.min(0), (int)i2.min(1), (int)i2.dimension(0), (int)i2.dimension(1));
			return box1.intersection(box2);
		}
	}
}

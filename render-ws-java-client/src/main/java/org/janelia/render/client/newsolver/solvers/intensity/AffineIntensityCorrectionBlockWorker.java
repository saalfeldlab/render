package org.janelia.render.client.newsolver.solvers.intensity;

import mpicbg.models.AffineModel1D;
import mpicbg.models.IdentityModel;
import mpicbg.models.InterpolatedAffineModel1D;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.models.TranslationModel1D;
import net.imglib2.util.ValuePair;

import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.intensityadjust.AdjustBlock;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.assembly.ResultContainer;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMIntensityCorrectionParameters;
import org.janelia.render.client.newsolver.solvers.Worker;
import org.janelia.render.client.parameter.ZDistanceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class AffineIntensityCorrectionBlockWorker<M>
		extends Worker<ArrayList<AffineModel1D>, FIBSEMIntensityCorrectionParameters<M>> {

	private final FIBSEMIntensityCorrectionParameters<M> parameters;

	public AffineIntensityCorrectionBlockWorker(
			final BlockData<ArrayList<AffineModel1D>, FIBSEMIntensityCorrectionParameters<M>> blockData,
			final int numThreads
	) throws IOException {

		super(blockData, numThreads);
		parameters = blockData.solveTypeParameters();
	}

	/**
	 * runs the Worker
	 */
	@Override
	public List<BlockData<ArrayList<AffineModel1D>, FIBSEMIntensityCorrectionParameters<M>>> call()
			throws IOException, ExecutionException, InterruptedException, NoninvertibleModelException {

        final long startTime = System.currentTimeMillis();

		LOG.info("call: entry, renderStack={}, blockData={}", renderStack, blockData);

		fetchResolvedTiles();

		if (blockData.rtsc().getTileCount() == 0) {
			LOG.info("call: no tiles found, returning empty list");
			return new ArrayList<>();
		}

		final List<TileSpec> wrappedTiles = AdjustBlock.sortTileSpecs(blockData.rtsc());

		final Map<String, IntensityTile> coefficientTiles = computeCoefficients(wrappedTiles);

		coefficientTiles.forEach((tileId, tiles) -> {
			final ArrayList<AffineModel1D> models = new ArrayList<>();
			for (int i = 0; i < tiles.nSubTiles(); i++) {
				final InterpolatedAffineModel1D<?, ?> interpolatedModel = (InterpolatedAffineModel1D<?, ?>) tiles.getSubTile(i).getModel();
				models.add(interpolatedModel.createAffineModel1D());
			}
			blockData.getResults().recordModel(tileId, models);
		});

		LOG.info("call: exit, renderStack={}, blockData={}, processing took {} seconds",
                 renderStack, blockData.toDetailsString(), (System.currentTimeMillis() - startTime) / 1000.0);

		return List.of(blockData);
	}

	private void fetchResolvedTiles()
			throws IOException {
		final Bounds bounds = blockData.getOriginalBounds();
		final ResolvedTileSpecCollection rtsc = renderDataClient.getResolvedTiles(
				parameters.stack(),
				bounds.getMinZ(), bounds.getMaxZ(),
				null, // groupId,
				bounds.getMinX(), bounds.getMaxX(),
				bounds.getMinY(), bounds.getMaxY(),
				null, // matchPattern
				true);
		blockData.getResults().init(rtsc);
	}

	private Map<String, IntensityTile> computeCoefficients(final List<TileSpec> tiles)
			throws ExecutionException, InterruptedException {

		LOG.info("computeCoefficients: entry, renderStack={}, blockData={}", renderStack, blockData);

		final long maxCachedPixels = parameters.maxPixelCacheGb() * 1024L * 1024L * 1024L;  // assume 8bit images
		final ImageProcessorCache imageProcessorCache = (maxCachedPixels == 0)
				? ImageProcessorCache.DISABLED_CACHE
				: new ImageProcessorCache(maxCachedPixels, true, false);

		final Map<String, IntensityTile> coefficientTiles = splitIntoCoefficientTiles(tiles, imageProcessorCache);

		if (tiles.size() > 1) {
			solveForGlobalCoefficients(coefficientTiles);
		} else {
			LOG.info("computeCoefficients: skipping solve because there is only 1 tile, renderStack={}, blockData={}",
                    renderStack, blockData);
		}

		return coefficientTiles;
	}

	private HashMap<String, IntensityTile> splitIntoCoefficientTiles(
			final List<TileSpec> tiles,
			final ImageProcessorCache imageProcessorCache
	) throws InterruptedException, ExecutionException {

		if (tiles == null || tiles.isEmpty()) {
			LOG.info("splitIntoCoefficientTiles: skipping because there are no tiles, renderStack={}, blockData={}",
                     renderStack, blockData);
			return new HashMap<>();
		}

		LOG.info("splitIntoCoefficientTiles: entry, renderStack={}, blockData={}, tiles.size={}, zDistance={}",
                 renderStack, blockData, tiles.size(), parameters.zDistance());

		// generate coefficient tiles for all patches
		final HashMap<String, IntensityTile> coefficientTiles = generateCoefficientsTiles(tiles);

		final List<ValuePair<TileSpec, TileSpec>> patchPairs = findOverlappingPatches(tiles, parameters.zDistance());

		LOG.info("splitIntoCoefficientTiles: matching intensities, renderStack={}, blockData={}, patchPairs.size={}, numThreads={}",
                 renderStack, blockData, patchPairs.size(), numThreads);

		// for all pairs of images that do overlap, extract matching intensity values (intensity values that should be the same)
		final IntensityMatcher matcher = getIntensityMatcher(tiles, imageProcessorCache);
		final ExecutorService exec = Executors.newFixedThreadPool(numThreads);
		final List<Future<?>> matchTasks = new ArrayList<>();

		for (final ValuePair<TileSpec, TileSpec> patchPair : patchPairs) {
			final TileSpec p1 = patchPair.getA();
			final TileSpec p2 = patchPair.getB();
			final Runnable matchJob = () -> matcher.match(renderStack, p1, p2, coefficientTiles);
			matchTasks.add(exec.submit(matchJob));
		}

		for (final Future<?> result : matchTasks)
			result.get();

		if (parameters.equilibrationWeight() > 0.0) {

			LOG.info("splitIntoCoefficientTiles: adding equilibration matches, renderStack={}, blockData={}, equilibrationWeight={}",
                     renderStack, blockData, parameters.equilibrationWeight());

			final Map<String, Future<List<Double>>> tileIdToAverage = new HashMap<>();
			for (final TileSpec tile : tiles) {
				final Future<List<Double>> result = exec.submit(() -> matcher.computeAverages(tile));
				tileIdToAverage.put(tile.getTileId(), result);
				blockData.getResults().recordMatchedTile(tile.getIntegerZ(), tile.getTileId());
			}

			final ResultContainer<ArrayList<AffineModel1D>> results = blockData.getResults();
			for (final Entry<String, Future<List<Double>>> tileToAverages : tileIdToAverage.entrySet()) {
				results.recordAverages(tileToAverages.getKey(), tileToAverages.getValue().get());
			}
		}

		exec.shutdown();

		LOG.info("splitIntoCoefficientTiles: exit, renderStack={}, blockData={}, imageProcessorCache.getStats={}",
                 renderStack, blockData, imageProcessorCache.getStats());

		return coefficientTiles;
	}

	private IntensityMatcher getIntensityMatcher(
			final List<TileSpec> tiles,
			final ImageProcessorCache imageProcessorCache
	) {
		final MatchFilter sameLayerFilter;
		final MatchFilter crossLayerFilter;

		if (this.parameters.tetherCrossLayers()) {
			// tethering keeps only cross-layer matches whose intensities already coincide (cutoff 0); this requires
			// pixel-by-pixel matching, so histogram matching cannot be used, and applies to cross-layer pairs only
			// (a large same-layer shift is what 2D correction fixes)
			LOG.info("getIntensityMatcher: tethering cross-layers at coinciding intensities (forces pixel-by-pixel matching), renderStack={}, blockData={}",
					 renderStack, blockData);
			sameLayerFilter = new RansacMatchFilter();
			crossLayerFilter = new CutoffRansacMatchFilter(0.0);
		} else if (this.parameters.useRansacMatching()) {
			sameLayerFilter = new RansacMatchFilter();
			crossLayerFilter = sameLayerFilter;
		} else {
			sameLayerFilter = new HistogramMatchFilter();
			crossLayerFilter = sameLayerFilter;
		}

		final int meshResolution = (int) tiles.get(0).getMeshCellSize();
		return new IntensityMatcher(sameLayerFilter, crossLayerFilter, parameters, meshResolution, imageProcessorCache);
	}

	private  HashMap<String, IntensityTile> generateCoefficientsTiles(final Collection<TileSpec> patches) {

		final InterpolatedAffineModel1D<InterpolatedAffineModel1D<AffineModel1D, TranslationModel1D>, IdentityModel> modelTemplate =
				new InterpolatedAffineModel1D<>(
						new InterpolatedAffineModel1D<>(
								new AffineModel1D(), new TranslationModel1D(), parameters.lambdaTranslation()),
						new IdentityModel(), parameters.lambdaIdentity());

		final HashMap<String, IntensityTile> coefficientTiles = new HashMap<>();
		for (final TileSpec p : patches) {
			final IntensityTile tile = new IntensityTile(modelTemplate::copy, parameters.numCoefficients(), 1);
			coefficientTiles.put(p.getTileId(), tile);
		}
		return coefficientTiles;
	}

	private static ArrayList<ValuePair<TileSpec, TileSpec>> findOverlappingPatches(
			final List<TileSpec> allPatches,
			final ZDistanceParameters zDistance
	) {
		// find the images that actually overlap (only for those we can extract intensity PointMatches)
		final ArrayList<ValuePair<TileSpec, TileSpec>> patchPairs = new ArrayList<>();
		final Set<TileSpec> unconsideredPatches = new HashSet<>(allPatches);

		for (final TileSpec p1 : allPatches) {
			unconsideredPatches.remove(p1);
			final TileBounds p1Bounds = p1.toTileBounds();
			final Rectangle xy1 = p1Bounds.toRectangle();

			for (final TileSpec p2 : unconsideredPatches) {
				final TileBounds p2Bounds = p2.toTileBounds();
				final Rectangle xy2 = p2Bounds.toRectangle();
				final Rectangle overlap = xy1.intersection(xy2);

				if ((overlap.getWidth() > 0) && (overlap.getHeight() > 0)
						&& zDistance.includePair(p1Bounds, p2Bounds))
					patchPairs.add(new ValuePair<>(p1, p2));
			}
		}
		return patchPairs;
	}

	@SuppressWarnings("SameParameterValue")
	private void solveForGlobalCoefficients(final Map<String, IntensityTile> coefficientTiles) {

        final String stackAndBlockForLog = "stack=" + renderStack + ", blockData=" + blockData;
        LOG.info("solveForGlobalCoefficients: entry, {}, coefficientTiles.size={}, numThreads={}",
                 stackAndBlockForLog, coefficientTiles.size(), numThreads);

		final IntensityTile equilibrationTile = new IntensityTile(IdentityModel::new, 1, 1);

		connectTilesWithinPatches(coefficientTiles, equilibrationTile);

		/* optimize */
		final List<IntensityTile> tiles = new ArrayList<>(coefficientTiles.values());

		// anchor the equilibration tile if it is used, otherwise pin a representative seam sub-tile
		final IntensityTile fixedTile;
		final int fixedSubTileIndex;
		if (blockData.solveTypeParameters().equilibrationWeight() > 0.0) {
			tiles.add(equilibrationTile);
			fixedTile = equilibrationTile;
			fixedSubTileIndex = 0;
		} else {
			final FixedAnchor anchor = selectFixedAnchor(coefficientTiles);
			fixedTile = anchor.tile();
			fixedSubTileIndex = anchor.subTileIndex();
		}

		final IntensityTileOptimizer optimizer = new IntensityTileOptimizer(
				blockData.solveTypeParameters().maxAllowedError(),
				blockData.solveTypeParameters().maxIterations(),
				blockData.solveTypeParameters().maxPlateauWidth(),
				1.0,
				numThreads);
		optimizer.optimize(tiles, fixedTile, fixedSubTileIndex, stackAndBlockForLog);

		// TODO: this is not the right error measure, what is idToBlockErrorMap supposed to be exactly?
		coefficientTiles.forEach((tileId, tile) -> {
			tile.updateDistance();
			final double error = tile.getDistance();
			final Map<String, Double> errorMap = new HashMap<>();
			errorMap.put(tileId, error);
			blockData.getResults().recordAllErrors(tileId, errorMap);
		});

		LOG.info("solveForGlobalCoefficients: exit, {}, returning intensity coefficients for {} tiles",
                 stackAndBlockForLog, coefficientTiles.size());
	}

	/** The sub-tile pinned as the gauge anchor: its owning {@link IntensityTile} and the index of the held sub-tile. */
	private static final class FixedAnchor {
		private final IntensityTile tile;
		private final int subTileIndex;
		private FixedAnchor(final IntensityTile tile, final int subTileIndex) {
			this.tile = tile;
			this.subTileIndex = subTileIndex;
		}
		private IntensityTile tile() { return tile; }
		private int subTileIndex() { return subTileIndex; }
	}

	/**
	 * Choose a deterministic, representative gauge anchor (pinned sub-tile) for the gauge-free per-block solve.
	 * Pinning an extreme (e.g. the brightest) sub-tile can leave a visible bright gradient with sharp seams,
	 * depending on regularization (especially with identity). To avoid that, we pin the sub-tile that already
	 * agrees best with its neighbors across tile boundaries. Only tiles actually overlap with neighbors are eligible.
	 */
	private FixedAnchor selectFixedAnchor(final Map<String, IntensityTile> coefficientTiles) {

		String bestTileId = null;
		IntensityTile bestTile = null;
		int bestIndex = -1;
		double bestMismatch = Double.POSITIVE_INFINITY;

		// Iterate tileIds in sorted order so ties resolve deterministically
		for (final String tileId : new TreeSet<>(coefficientTiles.keySet())) {
			final IntensityTile tile = coefficientTiles.get(tileId);
			for (int i = 0; i < tile.nSubTiles(); i++) {
				final Tile<?> subTile = tile.getSubTile(i);
				subTile.updateCost(); // residual under the current (identity) model
				final double mismatch = subTile.getDistance();

				// Skip sub-tiles not overlapping with anything (identity connections have error of 0 initially)
				if (mismatch <= 0.0) {
					continue;
				}

				if (mismatch < bestMismatch) {
					bestMismatch = mismatch;
					bestTileId = tileId;
					bestTile = tile;
					bestIndex = i;
				}
			}
		}

		if (bestTile == null) {
			// No overlapping sub-tiles (e.g., a single tile): fall back to the first tile
			final Entry<String, IntensityTile> first = coefficientTiles.entrySet().iterator().next();
			bestTile = first.getValue();
			bestIndex = 0;
			LOG.info("selectFixedAnchor: no seam sub-tiles found, falling back to tile {}, sub-tile 0", first.getKey());
		} else {
			LOG.info("selectFixedAnchor: anchoring on tile {} sub-tile {} (initial mismatch {})",
					 bestTileId, bestIndex, bestMismatch);
		}

		return new FixedAnchor(bestTile, bestIndex);
	}

	private void connectTilesWithinPatches(
			final Map<String, IntensityTile> coefficientTiles,
			final IntensityTile equilibrationTile
	) {
		final Collection<TileSpec> allTiles = blockData.rtsc().getTileSpecs();
		final double equilibrationWeight = blockData.solveTypeParameters().equilibrationWeight();

		final ResultContainer<ArrayList<AffineModel1D>> results = blockData.getResults();
		for (final TileSpec p : allTiles) {
			final IntensityTile coefficientTile = coefficientTiles.get(p.getTileId());
			for (int i = 1; i < parameters.numCoefficients(); ++i) {
				for (int j = 0; j < parameters.numCoefficients(); ++j) {
					final Tile<?> left = coefficientTile.getSubTile(i-1, j);
					final Tile<?> right = coefficientTile.getSubTile(i, j);
					final Tile<?> top = coefficientTile.getSubTile(j, i);
					final Tile<?> bot = coefficientTile.getSubTile(j, i-1);

					identityConnect(right, left);
					identityConnect(top, bot);
				}
			}
			if (equilibrationWeight > 0.0) {
				final List<Double> averages = results.getAveragesFor(p.getTileId());
				coefficientTile.connectTo(equilibrationTile);
				for (int i = 0; i < coefficientTile.nSubTiles(); i++) {
					equilibrateIntensity(coefficientTile.getSubTile(i),
										 equilibrationTile.getSubTile(0),
										 averages.get(i),
										 equilibrationWeight);
				}
			}
		}
	}

	private static void equilibrateIntensity(final Tile<?> coefficientTile,
											 final Tile<?> equilibrationTile,
											 final Double average,
											 final double weight) {
		final PointMatch eqMatch = new PointMatch1D(new Point1D(average), new Point1D(0.5), weight);
		coefficientTile.connect(equilibrationTile, List.of(eqMatch));
	}

	static protected void identityConnect(final Tile<?> t1, final Tile<?> t2) {
		final ArrayList<PointMatch> matches = new ArrayList<>();
		matches.add(new PointMatch1D(new Point1D(0), new Point1D(0)));
		matches.add(new PointMatch1D(new Point1D(1), new Point1D(1)));
		t1.connect(t2, matches);
	}

	private static final Logger LOG = LoggerFactory.getLogger(AffineIntensityCorrectionBlockWorker.class);
}

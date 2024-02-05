package org.janelia.render.client.newsolver;

import mpicbg.models.Affine1D;
import mpicbg.models.AffineModel1D;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.TranslationModel1D;
import org.janelia.alignment.filter.Filter;
import org.janelia.alignment.filter.FilterSpec;
import org.janelia.alignment.filter.IntensityMap8BitFilter;
import org.janelia.alignment.filter.LinearIntensityMap8BitFilter;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.intensityadjust.virtual.LinearOnTheFlyIntensity;
import org.janelia.render.client.intensityadjust.virtual.OnTheFlyIntensity;
import org.janelia.render.client.newsolver.assembly.Assembler;
import org.janelia.render.client.newsolver.assembly.BlockCombiner;
import org.janelia.render.client.newsolver.assembly.GlobalSolver;
import org.janelia.render.client.newsolver.assembly.ResultContainer;
import org.janelia.render.client.newsolver.assembly.matches.SameTileMatchCreatorAffineIntensity;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;
import org.janelia.render.client.newsolver.blockfactories.MergingStrategy;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMIntensityCorrectionParameters;
import org.janelia.render.client.newsolver.setup.IntensityCorrectionSetup;
import org.janelia.render.client.newsolver.setup.RenderSetup;
import org.janelia.render.client.newsolver.solvers.Worker;
import org.janelia.render.client.parameter.AlgorithmicIntensityAdjustParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static org.janelia.render.client.newsolver.DistributedAffineBlockSolver.getRandomIndex;


public class DistributedIntensityCorrectionSolver {
	final IntensityCorrectionSetup solverSetup;
	final RenderSetup renderSetup;
	BlockCollection<?, ArrayList<AffineModel1D>, ? extends FIBSEMIntensityCorrectionParameters<?>> blocks;
	BlockFactory blockFactory;

	public DistributedIntensityCorrectionSolver(
			final IntensityCorrectionSetup solverSetup,
			final RenderSetup renderSetup) {
		this.solverSetup = solverSetup;
		this.renderSetup = renderSetup;
	}

	public static void main(final String[] args) throws IOException {
		final IntensityCorrectionSetup cmdLineSetup = new IntensityCorrectionSetup();

		// TODO: remove testing hack ...
		if (args.length == 0) {
			final String[] testArgs = {
					"--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
					"--owner", "hess_wafer_53",
					"--project", "cut_000_to_009",
					"--stack", "c009_s310_v01_mfov_08_exact",
					"--targetStack", "c009_s310_v01_mfov_08_ic_test_new",
					"--threadsWorker", "2",
					"--threadsGlobal", "1",
					"--blockSizeZ", "6",
					"--completeTargetStack",
					// for entire stack minZ is 1 and maxZ is 14,503
					"--zDistance", "0", "--minZ", "440", "--maxZ", "441",
			};
			cmdLineSetup.parse(testArgs);
		} else {
			cmdLineSetup.parse(args);
		}

		run(cmdLineSetup);
	}

	public static void run(final IntensityCorrectionSetup cmdLineSetup) throws IOException {
		final RenderSetup renderSetup = RenderSetup.setupSolve(cmdLineSetup);

		// Note: different setups can be used if specific things need to be done for the solve or certain blocks
		final DistributedIntensityCorrectionSolver intensitySolver =
				new DistributedIntensityCorrectionSolver(cmdLineSetup, renderSetup);

		// create all block instances
		final BlockCollection<?, ArrayList<AffineModel1D>, ?> blockCollection = intensitySolver.setupSolve();

		final ArrayList<BlockData<ArrayList<AffineModel1D>, ?>> allItems =
				intensitySolver.solveBlocksUsingThreadPool(blockCollection);

		solveCombineAndSaveBlocks(cmdLineSetup, allItems, intensitySolver);
	}

	public static void solveCombineAndSaveBlocks(final IntensityCorrectionSetup cmdLineSetup,
												 final List<BlockData<ArrayList<AffineModel1D>, ?>> allItems,
												 final DistributedIntensityCorrectionSolver solver)
			throws IOException {

		final ResultContainer<ArrayList<AffineModel1D>> finalizedItems = solver.assembleBlocks(allItems);

		saveResultsAsNeeded(finalizedItems, solver.solverSetup);
	}

	public ArrayList<BlockData<ArrayList<AffineModel1D>, ?>> solveBlocksUsingThreadPool(final BlockCollection<?, ArrayList<AffineModel1D>, ?> blockCollection) {

		LOG.info("solveBlocksUsingThreadPool: entry, threadsGlobal={}", solverSetup.distributedSolve.threadsGlobal);

		final ArrayList<Callable<List<BlockData<ArrayList<AffineModel1D>, ?>>>> workers = new ArrayList<>();
		blockCollection.allBlocks().forEach(block -> workers.add(() -> createAndRunWorker(block, solverSetup)));

		final ArrayList<BlockData<ArrayList<AffineModel1D>, ?>> allItems = new ArrayList<>();
		final ExecutorService taskExecutor = Executors.newFixedThreadPool(solverSetup.distributedSolve.threadsGlobal);
		try {
			for (final Future<List<BlockData<ArrayList<AffineModel1D>, ?>>> future : taskExecutor.invokeAll(workers))
					allItems.addAll(future.get());
		} catch (final InterruptedException | ExecutionException e) {
			throw new RuntimeException("Failed to compute intensity correction", e);
		} finally {
			taskExecutor.shutdown();
		}

		LOG.info("solveBlocksUsingThreadPool: exit, computed {} blocks", allItems.size());

		return allItems;
	}

	public static List<BlockData<ArrayList<AffineModel1D>, ?>> createAndRunWorker(final BlockData<ArrayList<AffineModel1D>, ?> block,
																				  final IntensityCorrectionSetup solverSetup)
			throws IOException, ExecutionException, InterruptedException, NoninvertibleModelException {

		final Worker<ArrayList<AffineModel1D>, ?> worker = block.createWorker(solverSetup.distributedSolve.threadsWorker);
		return new ArrayList<>(worker.call());
	}

	public ResultContainer<ArrayList<AffineModel1D>> assembleBlocks(final List<BlockData<ArrayList<AffineModel1D>, ?>> allItems) {

		LOG.info("assembleBlocks: entry, processing {} blocks", allItems.size());

		final BlockCombiner<ArrayList<AffineModel1D>, ArrayList<AffineModel1D>, TranslationModel1D, ArrayList<AffineModel1D>> fusion =
				chooseCombiner(blockFactory);

		final GlobalSolver<TranslationModel1D, ArrayList<AffineModel1D>> globalSolver =
				new GlobalSolver<>(new TranslationModel1D(),
								   new SameTileMatchCreatorAffineIntensity(),
								   solverSetup.distributedSolve);

		final Assembler<ArrayList<AffineModel1D>, TranslationModel1D, ArrayList<AffineModel1D>> assembler =
				new Assembler<>(globalSolver, fusion, r -> {
					final ArrayList<AffineModel1D> rCopy = new ArrayList<>(r.size());
					r.forEach(model -> rCopy.add(model.copy()));
					return rCopy;});

		LOG.info("assembleBlocks: exit");

		return assembler.createAssembly(allItems, blockFactory);
	}

	private static BlockCombiner<ArrayList<AffineModel1D>, ArrayList<AffineModel1D>, TranslationModel1D, ArrayList<AffineModel1D>>
	chooseCombiner(final BlockFactory blockFactory) {
		final MergingStrategy mergingStrategy = blockFactory.getMergingStrategy();
		final BlockCombiner<ArrayList<AffineModel1D>, ArrayList<AffineModel1D>, TranslationModel1D, ArrayList<AffineModel1D>> fusion;
		if (mergingStrategy.equals(MergingStrategy.LINEAR_BLENDING)) {
			fusion = new BlockCombiner<>(DistributedIntensityCorrectionSolver::integrateGlobalTranslation,
										 DistributedIntensityCorrectionSolver::interpolateModels);
		} else if (mergingStrategy.equals(MergingStrategy.RANDOM_PICK)) {
			fusion = new BlockCombiner<>(DistributedIntensityCorrectionSolver::integrateGlobalTranslation,
										 DistributedIntensityCorrectionSolver::pickRandom);
		} else {
			throw new IllegalStateException("unknown merging strategy " + mergingStrategy);
		}
		return fusion;
	}

	public static void saveResultsAsNeeded(final ResultContainer<ArrayList<AffineModel1D>> finalizedItems, final IntensityCorrectionSetup solverSetup)
			throws IOException {
		// this adds the filters to the tile specs and pushes the data to the DB
		final boolean saveResults = (solverSetup.targetStack.stack != null);
		final RenderDataClient renderDataClient = solverSetup.renderWeb.getDataClient();
		if (saveResults) {
			final ResolvedTileSpecCollection rtsc = finalizedItems.getResolvedTileSpecs();
			final List<TileSpec> tileSpecs = new ArrayList<>(rtsc.getTileSpecs());
			final Map<String, ArrayList<AffineModel1D>> coefficientTiles = finalizedItems.getModelMap();
			final Map<String, FilterSpec> idToFilterSpec = convertCoefficientsToFilter(tileSpecs, coefficientTiles, solverSetup.intensityAdjust.numCoefficients);
			addFilters(rtsc, idToFilterSpec);
			renderDataClient.saveResolvedTiles(rtsc, solverSetup.targetStack.stack, null);
			if (solverSetup.targetStack.completeStack)
				renderDataClient.setStackState(solverSetup.targetStack.stack, StackMetaData.StackState.COMPLETE);
		}
	}

	private static ArrayList<AffineModel1D> integrateGlobalTranslation(final ArrayList<AffineModel1D> localModels, final TranslationModel1D globalModel) {
		final AffineModel1D affineTranslationWrapper = new AffineModel1D();
		affineTranslationWrapper.set(globalModel);
		final ArrayList<AffineModel1D> fusedModels = new ArrayList<>();
		for (final AffineModel1D affine : localModels) {
			final AffineModel1D affineCopy = affine.copy();
			affineCopy.preConcatenate(affineTranslationWrapper);
			fusedModels.add(affineCopy);
		}
		return fusedModels;
	}

	// TODO: refactor this to use 1D version of AlignmentModel if and when it exists
	private static ArrayList<AffineModel1D> interpolateModels(final List<ArrayList<AffineModel1D>> tiledModels, final List<Double> weights) {
		if (tiledModels.isEmpty() || tiledModels.size() != weights.size())
			throw new IllegalArgumentException("models and weights must be non-empty and of the same size");

		if (tiledModels.size() == 1)
			return tiledModels.get(0);

		// normalize weights
		final double sumWeights = weights.stream().mapToDouble(v -> v).sum();
		final double[] normalizedWeights = weights.stream().mapToDouble(v -> v / sumWeights).toArray();

		final int nCoefficients = 2;
		final int nModelsPerTile = tiledModels.get(0).size();
		final double[] c = new double[nCoefficients];
		final List<double[]> finalCoefficients = new ArrayList<>();
		for (int i = 0; i < nModelsPerTile; ++i)
			finalCoefficients.add(new double[nCoefficients]);

		// extract and interpolate coefficients
		for (int n = 0; n < tiledModels.size(); n++) {
			final List<AffineModel1D> models = tiledModels.get(n);
			final double w = normalizedWeights[n];
			for (int k = 0; k < models.size(); ++k) {
				models.get(k).toArray(c);
				final double[] cFinal = finalCoefficients.get(k);
				cFinal[0] += w * c[0];
				cFinal[1] += w * c[1];
			}
		}

		final ArrayList<AffineModel1D> interpolatedModels = new ArrayList<>();
		for (final double[] cFinal : finalCoefficients) {
			final AffineModel1D interpolatedModel = new AffineModel1D();
			interpolatedModel.set(cFinal[0], cFinal[1]);
			interpolatedModels.add(interpolatedModel);
		}
		return interpolatedModels;
	}

	// TODO: remove duplication with DistributedAffineBlockSolver
	private static ArrayList<AffineModel1D> pickRandom(final List<ArrayList<AffineModel1D>> models, final List<Double> weights) {
		if (models.isEmpty() || models.size() != weights.size())
			throw new IllegalArgumentException("models and weights must be non-empty and of the same size");

		if (models.size() == 1)
			return models.get(0);

		final double randomSample = ThreadLocalRandom.current().nextDouble();
		final int i = getRandomIndex(weights, randomSample);
		return models.get(i);
	}

	private static Map<String, FilterSpec> convertCoefficientsToFilter(
			final List<TileSpec> tiles,
			final Map<String, ArrayList<AffineModel1D>> coefficientTiles,
			final int numCoefficients) {

		final ArrayList<OnTheFlyIntensity> corrected = convertModelsToOtfIntensities(tiles, numCoefficients, coefficientTiles);

		final Map<String, FilterSpec> idToFilterSpec = new HashMap<>();
		for (final OnTheFlyIntensity onTheFlyIntensity : corrected) {
			final TileSpec tileSpec = onTheFlyIntensity.getTileSpec();
			final IntensityMap8BitFilter newFilter = onTheFlyIntensity.toFilter();
			final FilterSpec existingFilterSpec = tileSpec.getFilterSpec();
			if (existingFilterSpec != null) {
				checkAndMergeFilters(existingFilterSpec.buildInstance(), newFilter);
			}
			final FilterSpec filterSpec = new FilterSpec(newFilter.getClass().getName(), newFilter.toParametersMap());
			idToFilterSpec.put(tileSpec.getTileId(), filterSpec);
		}

		return idToFilterSpec;
	}

	private static ArrayList<OnTheFlyIntensity> convertModelsToOtfIntensities(
			final List<TileSpec> tiles,
			final int numCoefficients,
			final Map<String, ArrayList<AffineModel1D>> coefficientTiles) {

		final ArrayList<OnTheFlyIntensity> correctedOnTheFly = new ArrayList<>();
		for (final TileSpec tile : tiles) {
			/* save coefficients */
			final double[][] ab_coefficients = new double[numCoefficients * numCoefficients][2];

			final ArrayList<AffineModel1D> models = coefficientTiles.get(tile.getTileId());

			for (int i = 0; i < numCoefficients * numCoefficients; ++i) {
				final Affine1D<?> affine = models.get(i);
				affine.toArray(ab_coefficients[i]);
			}

			correctedOnTheFly.add(new LinearOnTheFlyIntensity(tile, ab_coefficients, numCoefficients ));
		}
		return correctedOnTheFly;
	}

	private static void checkAndMergeFilters(final Filter oldFilter, final IntensityMap8BitFilter newFilter) {
		if (oldFilter instanceof LinearIntensityMap8BitFilter
				&& newFilter instanceof LinearIntensityMap8BitFilter) {
			final LinearIntensityMap8BitFilter oldLinearFilter = (LinearIntensityMap8BitFilter) oldFilter;
			final LinearIntensityMap8BitFilter newLinearFilter = (LinearIntensityMap8BitFilter) newFilter;
			newLinearFilter.after(oldLinearFilter);
		} else {
			throw new IllegalArgumentException("Cannot merge filters of type " + oldFilter.getClass().getName() +
											   " and " + newFilter.getClass().getName());
		}
	}

	private static void addFilters(final ResolvedTileSpecCollection rtsc,
								   final Map<String, FilterSpec> idToFilterSpec) {
		idToFilterSpec.forEach((tileId, filterSpec) -> {
			final TileSpec tileSpec = rtsc.getTileSpec(tileId);
			tileSpec.setFilterSpec(filterSpec);
			tileSpec.convertSingleChannelSpecToLegacyForm();
		});
	}

	public <M> BlockCollection<M, ArrayList<AffineModel1D>, FIBSEMIntensityCorrectionParameters<M>> setupSolve() {
		this.blockFactory = BlockFactory.fromBlockSizes(renderSetup.getBounds(), solverSetup.blockPartition);
		final FIBSEMIntensityCorrectionParameters<M> defaultSolveParams = getDefaultParameters();
		final BlockCollection<M, ArrayList<AffineModel1D>, FIBSEMIntensityCorrectionParameters<M>> col =
				blockFactory.defineBlockCollection(() -> defaultSolveParams, solverSetup.blockPartition.shiftBlocks);

		this.blocks = col;
		return col;
	}

	protected <M> FIBSEMIntensityCorrectionParameters<M> getDefaultParameters() {
		final RenderWebServiceParameters renderWeb = solverSetup.renderWeb;
		final AlgorithmicIntensityAdjustParameters intensityAdjust = solverSetup.intensityAdjust;
		return new FIBSEMIntensityCorrectionParameters<>(null, renderWeb, intensityAdjust);
	}

	private static final Logger LOG = LoggerFactory.getLogger(DistributedIntensityCorrectionSolver.class);
}

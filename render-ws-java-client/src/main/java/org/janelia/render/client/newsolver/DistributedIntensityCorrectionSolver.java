package org.janelia.render.client.newsolver;

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

import mpicbg.models.Affine1D;
import mpicbg.models.AffineModel1D;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.TranslationModel1D;

import org.janelia.alignment.filter.FilterSpec;
import org.janelia.alignment.filter.IntensityMap8BitFilter;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.intensityadjust.MinimalTileSpecWrapper;
import org.janelia.render.client.intensityadjust.virtual.LinearOnTheFlyIntensity;
import org.janelia.render.client.intensityadjust.virtual.OnTheFlyIntensity;
import org.janelia.render.client.newsolver.assembly.Assembler;
import org.janelia.render.client.newsolver.assembly.BlockCombiner;
import org.janelia.render.client.newsolver.assembly.GlobalSolver;
import org.janelia.render.client.newsolver.assembly.ResultContainer;
import org.janelia.render.client.newsolver.assembly.matches.SameTileMatchCreatorAffineIntensity;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMIntensityCorrectionParameters;
import org.janelia.render.client.newsolver.setup.IntensityCorrectionSetup;
import org.janelia.render.client.newsolver.setup.RenderSetup;
import org.janelia.render.client.newsolver.solvers.Worker;
import org.janelia.render.client.parameter.AlgorithmicIntensityAdjustParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


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
					"--owner", "cellmap",
					"--project", "jrc_mus_thymus_1",
					"--stack", "v2_acquire_align",
					"--targetStack", "v2_acquire_test_intensity_debug13",
					"--threadsWorker", "1",
					"--threadsGlobal", "12",
					"--blockSizeZ", "6",
					"--completeTargetStack",
					// for entire stack minZ is 1 and maxZ is 14,503
					"--zDistance", "0", "--minZ", "1000", "--maxZ", "1001"
			};
			cmdLineSetup.parse(testArgs);
		} else {
			cmdLineSetup.parse(args);
		}

		final RenderSetup renderSetup = RenderSetup.setupSolve(cmdLineSetup);

		// Note: different setups can be used if specific things need to be done for the solve or certain blocks
		final DistributedIntensityCorrectionSolver intensitySolver =
				new DistributedIntensityCorrectionSolver(cmdLineSetup, renderSetup);

		// create all block instances
		final BlockCollection<?, ArrayList<AffineModel1D>, ?> blockCollection = intensitySolver.setupSolve();

		final ArrayList<BlockData<ArrayList<AffineModel1D>, ?>> allItems =
				intensitySolver.solveBlocksUsingThreadPool(blockCollection);

		final ResultContainer<ArrayList<AffineModel1D>> finalizedItems = intensitySolver.assembleBlocks(allItems);

		intensitySolver.saveResultsAsNeeded(finalizedItems);
	}

	public ArrayList<BlockData<ArrayList<AffineModel1D>, ?>> solveBlocksUsingThreadPool(final BlockCollection<?, ArrayList<AffineModel1D>, ?> blockCollection) {

		LOG.info("solveBlocksUsingThreadPool: entry, threadsGlobal={}", solverSetup.distributedSolve.threadsGlobal);

		final ArrayList<Callable<List<BlockData<ArrayList<AffineModel1D>, ?>>>> workers = new ArrayList<>();
		blockCollection.allBlocks().forEach(block -> workers.add(() -> createAndRunWorker(block)));

		final ArrayList<BlockData<ArrayList<AffineModel1D>, ?>> allItems = new ArrayList<>();
		final ExecutorService taskExecutor = Executors.newFixedThreadPool(solverSetup.distributedSolve.threadsGlobal);
		try {
			for (final Future<List<BlockData<ArrayList<AffineModel1D>, ?>>> future : taskExecutor.invokeAll(workers))
					allItems.addAll(future.get());
		} catch (final InterruptedException | ExecutionException e) {
			throw new RuntimeException("Failed to compute alignments", e);
		} finally {
			taskExecutor.shutdown();
		}

		LOG.info("solveBlocksUsingThreadPool: exit, computed {} blocks", allItems.size());

		return allItems;
	}

	private ArrayList<BlockData<ArrayList<AffineModel1D>, ?>> createAndRunWorker(final BlockData<ArrayList<AffineModel1D>, ?> block)
			throws IOException, ExecutionException, InterruptedException, NoninvertibleModelException {

		final Worker<ArrayList<AffineModel1D>, ?> worker = block.createWorker(solverSetup.distributedSolve.threadsWorker);
		worker.run();
		return new ArrayList<>(worker.getBlockDataList());
	}

	public ResultContainer<ArrayList<AffineModel1D>> assembleBlocks(final List<BlockData<ArrayList<AffineModel1D>, ?>> allItems) {

		LOG.info("assembleBlocks: entry, processing {} blocks", allItems.size());

		final BlockCombiner<ArrayList<AffineModel1D>, ArrayList<AffineModel1D>, TranslationModel1D, ArrayList<AffineModel1D>> fusion =
				new BlockCombiner<>(DistributedIntensityCorrectionSolver::integrateGlobalTranslation,
									DistributedIntensityCorrectionSolver::interpolateModels);

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

	public void saveResultsAsNeeded(final ResultContainer<ArrayList<AffineModel1D>> finalizedItems)
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

	// TODO: refactor this to use 1D version of AlingmentModel if and when it exists
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

	private static Map<String, FilterSpec> convertCoefficientsToFilter(
			final List<TileSpec> tiles,
			final Map<String, ArrayList<AffineModel1D>> coefficientTiles,
			final int numCoefficients) {

		final ArrayList<OnTheFlyIntensity> corrected = convertModelsToOtfIntensities(tiles, numCoefficients, coefficientTiles);

		final Map<String, FilterSpec> idToFilterSpec = new HashMap<>();
		for (final OnTheFlyIntensity onTheFlyIntensity : corrected) {
			final String tileId = onTheFlyIntensity.getMinimalTileSpecWrapper().getTileId();
			final IntensityMap8BitFilter filter = onTheFlyIntensity.toFilter();
			final FilterSpec filterSpec = new FilterSpec(filter.getClass().getName(), filter.toParametersMap());
			idToFilterSpec.put(tileId, filterSpec);
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

			correctedOnTheFly.add(new LinearOnTheFlyIntensity(new MinimalTileSpecWrapper(tile), ab_coefficients, numCoefficients ));
		}
		return correctedOnTheFly;
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
		this.blockFactory = BlockFactory.fromBlocksizes(renderSetup, solverSetup.blockPartition);
		final FIBSEMIntensityCorrectionParameters<M> defaultSolveParams = getDefaultParameters();
		final BlockCollection<M, ArrayList<AffineModel1D>, FIBSEMIntensityCorrectionParameters<M>> col =
				blockFactory.defineBlockCollection(() -> defaultSolveParams);

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

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
import mpicbg.models.InterpolatedAffineModel1D;
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
import org.janelia.render.client.newsolver.assembly.AssemblyMaps;
import org.janelia.render.client.newsolver.assembly.BlockSolver;
import org.janelia.render.client.newsolver.assembly.BlockCombiner;
import org.janelia.render.client.newsolver.assembly.matches.SameTileMatchCreatorAffineIntensity;
import org.janelia.render.client.newsolver.blockfactories.ZBlockFactory;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMIntensityCorrectionParameters;
import org.janelia.render.client.newsolver.setup.IntensityCorrectionSetup;
import org.janelia.render.client.newsolver.setup.RenderSetup;
import org.janelia.render.client.newsolver.solvers.Worker;
import org.janelia.render.client.newsolver.solvers.WorkerTools;
import org.janelia.render.client.parameter.AlgorithmicIntensityAdjustParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class DistributedIntensityCorrectionSolver {
	final IntensityCorrectionSetup cmdLineSetup;
	final RenderSetup renderSetup;
	BlockCollection<?, ArrayList<AffineModel1D>, ? extends FIBSEMIntensityCorrectionParameters<?>> blocks;
	ZBlockFactory blockFactory;

	public DistributedIntensityCorrectionSolver(
			final IntensityCorrectionSetup cmdLineSetup,
			final RenderSetup renderSetup) {
		this.cmdLineSetup = cmdLineSetup;
		this.renderSetup = renderSetup;
	}

	public static void main(final String[] args) throws IOException {
		final IntensityCorrectionSetup cmdLineSetup = new IntensityCorrectionSetup();

		// TODO: remove testing hack ...
		if (args.length == 0) {
			// TODO: figure out blocksize vs minblocksize
			final String[] testArgs = {
					"--baseDataUrl", "http://em-services-1.int.janelia.org:8080/render-ws/v1",
					"--owner", "cellmap",
					"--project", "jrc_mus_thymus_1",
					"--stack", "v2_acquire_align",
					"--targetStack", "v2_acquire_test_intensity_debug",
					"--threadsWorker", "12",
					"--threadsGlobal", "1",
					"--minBlockSize", "2",
					"--blockSize", "3",
					"--completeTargetStack",
					// for entire stack minZ is 1 and maxZ is 14,503
					"--zDistance", "1", "--minZ", "1000", "--maxZ", "1005"
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

		final ArrayList<BlockData<?, ArrayList<AffineModel1D>, ?>> allItems =
				intensitySolver.solveBlocksUsingThreadPool(blockCollection);

		final AssemblyMaps<ArrayList<AffineModel1D>> finalizedItems = intensitySolver.assembleBlocks(allItems);

		intensitySolver.saveResultsAsNeeded(finalizedItems);
	}

	public ArrayList<BlockData<?, ArrayList<AffineModel1D>, ?>> solveBlocksUsingThreadPool(final BlockCollection<?, ArrayList<AffineModel1D>, ?> blockCollection) {

		LOG.info("solveBlocksUsingThreadPool: entry, threadsGlobal={}", cmdLineSetup.distributedSolve.threadsGlobal);

		final ArrayList<Callable<List<BlockData<?, ArrayList<AffineModel1D>, ?>>>> workers = new ArrayList<>();
		for (final BlockData<?, ArrayList<AffineModel1D>, ?> block : blockCollection.allBlocks()) {
			workers.add(() ->
						{
							final Worker<?, ArrayList<AffineModel1D>, ?> worker = block.createWorker(
									blocks.maxId() + 1,
									cmdLineSetup.distributedSolve.threadsWorker);

							worker.run();

							return new ArrayList<>(worker.getBlockDataList());
						});
		}

		final ArrayList<BlockData<?, ArrayList<AffineModel1D>, ?>> allItems = new ArrayList<>();
		final ExecutorService taskExecutor = Executors.newFixedThreadPool(cmdLineSetup.distributedSolve.threadsGlobal);
		try {
			for (final Future<List<BlockData<?, ArrayList<AffineModel1D>, ?>>> future : taskExecutor.invokeAll(workers))
					allItems.addAll(future.get());
		} catch (final InterruptedException | ExecutionException e) {
			throw new RuntimeException("Failed to compute alignments", e);
		} finally {
			taskExecutor.shutdown();
		}

		// avoid duplicate id assigned while splitting solveitems in the workers
		// but do keep ids that are smaller or equal to the maxId of the initial solveset
		final int maxId = WorkerTools.fixIds(allItems, blocks.maxId());

		LOG.info("solveBlocksUsingThreadPool: exit, computed {} blocks, maxId={}", allItems.size(), maxId);

		return allItems;
	}

	public AssemblyMaps<ArrayList<AffineModel1D>> assembleBlocks(final List<BlockData<?, ArrayList<AffineModel1D>, ?>> allItems) {

		LOG.info("assembleBlocks: entry, processing {} blocks", allItems.size());

		final BlockSolver<ArrayList<AffineModel1D>, TranslationModel1D, ArrayList<AffineModel1D>> blockSolver =
				new BlockSolver<>(
						new TranslationModel1D(),
						new SameTileMatchCreatorAffineIntensity(),
						cmdLineSetup.distributedSolve.maxPlateauWidthGlobal,
						cmdLineSetup.distributedSolve.maxAllowedErrorGlobal,
						cmdLineSetup.distributedSolve.maxIterationsGlobal,
						cmdLineSetup.distributedSolve.threadsGlobal);

		final BlockCombiner<ArrayList<AffineModel1D>, ArrayList<AffineModel1D>, TranslationModel1D, ArrayList<AffineModel1D>> fusion =
				new BlockCombiner<>(blockSolver,
									DistributedIntensityCorrectionSolver::integrateGlobalTranslation,
									DistributedIntensityCorrectionSolver::combineWeightedModels);

		final Assembler<ArrayList<AffineModel1D>, TranslationModel1D, ArrayList<AffineModel1D>> assembler =
				new Assembler<>(allItems, blockSolver, fusion, r -> {
					final ArrayList<AffineModel1D> rCopy = new ArrayList<>(r.size());
					r.forEach(model -> rCopy.add(model.copy()));
					return rCopy;
				});

		LOG.info("assembleBlocks: exit");

		return assembler.createAssembly();
	}

	public void saveResultsAsNeeded(final AssemblyMaps<ArrayList<AffineModel1D>> finalizedItems)
			throws IOException {
		// this adds the filters to the tile specs and pushes the data to the DB
		final boolean saveResults = (cmdLineSetup.targetStack.stack != null);
		final RenderDataClient renderDataClient = cmdLineSetup.renderWeb.getDataClient();
		if (saveResults) {
			final List<TileSpec> tileSpecs = new ArrayList<>(finalizedItems.idToTileSpecGlobal.values());
			final HashMap<String, ArrayList<AffineModel1D>> coefficientTiles = finalizedItems.idToFinalModelGlobal;
			final Map<String, FilterSpec> idToFilterSpec = convertCoefficientsToFilter(tileSpecs, coefficientTiles, cmdLineSetup.intensityAdjust.numCoefficients);
			addFilters(finalizedItems.idToTileSpecGlobal, idToFilterSpec);
			final ResolvedTileSpecCollection rtsc = finalizedItems.buildResolvedTileSpecs();
			renderDataClient.saveResolvedTiles(rtsc, cmdLineSetup.targetStack.stack, null);
			if (cmdLineSetup.targetStack.completeStack)
				renderDataClient.setStackState(cmdLineSetup.targetStack.stack, StackMetaData.StackState.COMPLETE);
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

	private static ArrayList<AffineModel1D> combineWeightedModels(final List<ArrayList<AffineModel1D>> models, final List<Double> weights) {
		final ArrayList<AffineModel1D> coeffsBlockA = models.get(0);
		final int n = coeffsBlockA.size();
		final ArrayList<AffineModel1D> fusedCoeffs = new ArrayList<>(n);

		// TODO: make this run for more than two blocks
		if (models.size() == 1) {
			fusedCoeffs.addAll(coeffsBlockA);
		} else if (models.size() == 2){
			final ArrayList<AffineModel1D> coeffsBlockB = models.get(1);
			final double lambda = weights.get(1);
			for (int i = 0; i < n; i++)
				fusedCoeffs.add(new InterpolatedAffineModel1D<>(coeffsBlockA.get(i), coeffsBlockB.get(i), lambda).createAffineModel1D());
		} else {
			throw new UnsupportedOperationException("Combining more than two blocks is not yet supported");
		}
		return fusedCoeffs;
	}

	private static Map<String, FilterSpec> convertCoefficientsToFilter(
			final List<TileSpec> tiles,
			final HashMap<String, ArrayList<AffineModel1D>> coefficientTiles,
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

	private static void addFilters(final Map<String, TileSpec> idToTileSpec,
								   final Map<String, FilterSpec> idToFilterSpec) {
		idToFilterSpec.forEach((tileId, filterSpec) -> {
			final TileSpec tileSpec = idToTileSpec.get(tileId);
			tileSpec.setFilterSpec(filterSpec);
			tileSpec.convertSingleChannelSpecToLegacyForm();
		});
	}

	public <M> BlockCollection<M, ArrayList<AffineModel1D>, FIBSEMIntensityCorrectionParameters<M>> setupSolve() {

		final int minZ = (int) Math.round(renderSetup.minZ);
		final int maxZ = (int) Math.round(renderSetup.maxZ);
		final int blockSize = cmdLineSetup.distributedSolve.blockSize;
		final int minBlockSize = cmdLineSetup.distributedSolve.minBlockSize;
		this.blockFactory = new ZBlockFactory(minZ, maxZ, blockSize, minBlockSize);

		final FIBSEMIntensityCorrectionParameters<M> defaultSolveParams = getDefaultParameters();
		final BlockCollection<M, ArrayList<AffineModel1D>, FIBSEMIntensityCorrectionParameters<M>> col =
				blockFactory.defineBlockCollection(rtsc -> defaultSolveParams);

		this.blocks = col;
		return col;
	}

	protected <M> FIBSEMIntensityCorrectionParameters<M> getDefaultParameters() {
		final RenderWebServiceParameters renderWeb = cmdLineSetup.renderWeb;
		final AlgorithmicIntensityAdjustParameters intensityAdjust = cmdLineSetup.intensityAdjust;
		return new FIBSEMIntensityCorrectionParameters<>(null, renderWeb, intensityAdjust);
	}

	private static final Logger LOG = LoggerFactory.getLogger(DistributedIntensityCorrectionSolver.class);
}

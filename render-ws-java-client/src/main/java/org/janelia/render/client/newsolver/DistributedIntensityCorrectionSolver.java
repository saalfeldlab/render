package org.janelia.render.client.newsolver;

import mpicbg.models.AffineModel1D;
import mpicbg.models.TranslationModel1D;
import org.janelia.render.client.newsolver.assembly.Assembler;
import org.janelia.render.client.newsolver.assembly.ZBlockSolver;
import org.janelia.render.client.newsolver.assembly.matches.SameTileMatchCreatorAffineIntensity;
import org.janelia.render.client.newsolver.blockfactories.ZBlockFactory;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMIntensityCorrectionParameters;
import org.janelia.render.client.newsolver.setup.IntensityCorrectionSetup;
import org.janelia.render.client.newsolver.setup.RenderSetup;
import org.janelia.render.client.newsolver.solvers.Worker;
import org.janelia.render.client.newsolver.solvers.WorkerTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DistributedIntensityCorrectionSolver {
	final IntensityCorrectionSetup cmdLineSetup;
	final RenderSetup renderSetup;
	BlockCollection<?, ArrayList<AffineModel1D>, ? extends FIBSEMIntensityCorrectionParameters<?>, ZBlockFactory> col;
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
			final String[] testArgs = {
					"--baseDataUrl", "http://em-services-1.int.janelia.org:8080/render-ws/v1",
					"--owner", "Z0720_07m_BR",
					"--project", "Sec24",
					"--stack", "v5_acquire_trimmed_align",
					"--targetStack", "v5_acquire_trimmed_test",
					"--completeCorrectedStack",
					"--numThreads", "12",
					// for entire stack minZ is 1 and maxZ is 63,300
					"--zDistance", "1", "--minZ", "1000", "--maxZ", "1001"
			};
			cmdLineSetup.parse(testArgs);
		} else {
			cmdLineSetup.parse(args);
		}

		final RenderSetup renderSetup = RenderSetup.setupSolve(cmdLineSetup);

		// Note: different setups can be used if specific things need to be done for the solve or certain blocks
		final DistributedIntensityCorrectionSolver solverSetup = new DistributedIntensityCorrectionSolver(cmdLineSetup, renderSetup);

		// create all block instances
		final BlockCollection<?, ArrayList<AffineModel1D>, ?, ZBlockFactory> blockCollection = solverSetup.setupSolve();

		//
		// multi-threaded solve
		//
		LOG.info("Multithreading with thread num=" + cmdLineSetup.threadsGlobal);

		final ArrayList<Callable<List<BlockData<?, ArrayList<AffineModel1D>, ?, ZBlockFactory>>>> workers = new ArrayList<>();


		for (final BlockData<?, ArrayList<AffineModel1D>, ?, ZBlockFactory> block : blockCollection.allBlocks()) {
			workers.add(() ->
						{
							final Worker<?, ArrayList<AffineModel1D>, ?, ZBlockFactory> worker = block.createWorker(
									solverSetup.col.maxId() + 1,
									cmdLineSetup.threadsWorker);

							worker.run();

							return new ArrayList<>(worker.getBlockDataList());
						});
		}

		final ArrayList<BlockData<?, ArrayList<AffineModel1D>, ?, ZBlockFactory>> allItems = new ArrayList<>();

		try {
			final ExecutorService taskExecutor = Executors.newFixedThreadPool(cmdLineSetup.threadsGlobal);

			taskExecutor.invokeAll(workers).forEach(future -> {
				try {
					allItems.addAll(future.get());
				} catch (final InterruptedException | ExecutionException e) {
					LOG.error("Failed to compute alignments: " + e);
					e.printStackTrace();
				}
			});

			taskExecutor.shutdown();
		} catch (final InterruptedException e) {
			LOG.error("Failed to compute alignments: " + e);
			e.printStackTrace();
			return;
		}

		// avoid duplicate id assigned while splitting solveitems in the workers
		// but do keep ids that are smaller or equal to the maxId of the initial solveset
		final int maxId = WorkerTools.fixIds(allItems, solverSetup.col.maxId());

		LOG.info("computed " + allItems.size() + " blocks, maxId=" + maxId);

		final ZBlockSolver<ArrayList<AffineModel1D>, TranslationModel1D, ArrayList<AffineModel1D>> solver =
				new ZBlockSolver<>(
						new TranslationModel1D(),
						new SameTileMatchCreatorAffineIntensity(),
						cmdLineSetup.distributedSolve.maxPlateauWidthGlobal,
						cmdLineSetup.distributedSolve.maxAllowedErrorGlobal,
						cmdLineSetup.distributedSolve.maxIterationsGlobal,
						cmdLineSetup.threadsGlobal);

		final Assembler<ArrayList<AffineModel1D>, TranslationModel1D, ArrayList<AffineModel1D>, ZBlockFactory> assembler = new Assembler<>(allItems, solver);
		assembler.createAssembly();
	}

	public <M> BlockCollection<M, ArrayList<AffineModel1D>, FIBSEMIntensityCorrectionParameters<M>, ZBlockFactory> setupSolve() {

		final ZBlockFactory blockFactory = setupBlockFactory();
		this.blockFactory = blockFactory;

		final BlockCollection<M, ArrayList<AffineModel1D>, FIBSEMIntensityCorrectionParameters<M>, ZBlockFactory> col =
				setupBlockCollection(blockFactory);

		this.col = col;

		return col;
	}

	protected ZBlockFactory setupBlockFactory() {
		final int minZ = (int) Math.round(renderSetup.minZ);
		final int maxZ = (int) Math.round(renderSetup.maxZ);
		final int blockSize = cmdLineSetup.distributedSolve.blockSize;
		final int minBlockSize = cmdLineSetup.distributedSolve.minBlockSize;

		return new ZBlockFactory(minZ, maxZ, blockSize, minBlockSize);
	}

	protected <M> FIBSEMIntensityCorrectionParameters<M> setupSolveParameters() {

		return new FIBSEMIntensityCorrectionParameters<>(
				null,
				cmdLineSetup.renderWeb.baseDataUrl,
				cmdLineSetup.renderWeb.owner,
				cmdLineSetup.renderWeb.project,
				cmdLineSetup.stack,
				cmdLineSetup.intensityCorrectedFilterStack,
				cmdLineSetup.maxPixelCacheGb,
				cmdLineSetup.lambdaTranslation,
				cmdLineSetup.lambdaIdentity,
				cmdLineSetup.renderScale,
				cmdLineSetup.numCoefficients,
				cmdLineSetup.zDistance);
	}

	protected <M> BlockCollection<M, ArrayList<AffineModel1D>, FIBSEMIntensityCorrectionParameters<M>, ZBlockFactory> setupBlockCollection( final ZBlockFactory blockFactory){

		final FIBSEMIntensityCorrectionParameters<M> defaultSolveParams = setupSolveParameters();
		return blockFactory.defineBlockCollection(rtsc -> defaultSolveParams);
	}

	private static final Logger LOG = LoggerFactory.getLogger(DistributedIntensityCorrectionSolver.class);
}

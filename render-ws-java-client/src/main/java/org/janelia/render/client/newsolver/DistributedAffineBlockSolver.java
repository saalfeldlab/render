package org.janelia.render.client.newsolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.RigidModel2D;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.newsolver.assembly.Assembler;
import org.janelia.render.client.newsolver.assembly.ResultContainer;
import org.janelia.render.client.newsolver.assembly.BlockCombiner;
import org.janelia.render.client.newsolver.assembly.BlockSolver;
import org.janelia.render.client.newsolver.assembly.matches.SameTileMatchCreatorAffine2D;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMAlignmentParameters;
import org.janelia.render.client.newsolver.setup.AffineBlockSolverSetup;
import org.janelia.render.client.newsolver.setup.RenderSetup;
import org.janelia.render.client.newsolver.solvers.Worker;
import org.janelia.render.client.newsolver.solvers.WorkerTools;
import org.janelia.render.client.newsolver.solvers.affine.AlignmentModel;
import org.janelia.render.client.solver.RunParameters;
import org.janelia.render.client.solver.SolveTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.Model;

import static org.janelia.render.client.newsolver.solvers.affine.AlignmentModel.AlignmentModelBuilder;

public class DistributedAffineBlockSolver
{
	final AffineBlockSolverSetup solverSetup;
	final RenderSetup renderSetup;
	BlockCollection<?, AffineModel2D, ? extends FIBSEMAlignmentParameters<?, ?>> col;
	BlockFactory blockFactory;

	public DistributedAffineBlockSolver(
			final AffineBlockSolverSetup solverSetup,
			final RenderSetup renderSetup) {
		this.solverSetup = solverSetup;
		this.renderSetup = renderSetup;
	}

	public static void main( final String[] args ) throws IOException
	{
        final AffineBlockSolverSetup cmdLineSetup = new AffineBlockSolverSetup();

        // Pointmatch explorer link to the used dataset
        // http://em-services-1.int.janelia.org:8080/render-ws/view/point-match-explorer.html?renderStackOwner=hess&dynamicRenderHost=renderer.int.janelia.org%3A8080&catmaidHost=renderer-catmaid.int.janelia.org%3A8000&matchOwner=hess&renderDataHost=em-services-1.int.janelia.org%3A8080&ndvizHost=renderer.int.janelia.org%3A8080&renderStackProject=wafer_52c&renderStack=v1_acquire_slab_001_trimmed&startZ=1241&endZ=1241&matchCollection=wafer_52c_v4
        // Render stacks
        // http://em-services-1.int.janelia.org:8080/render-ws/view/stacks.html?renderStackOwner=hess&renderStackProject=wafer_52c&dynamicRenderHost=renderer.int.janelia.org%3A8080&catmaidHost=renderer-catmaid.int.janelia.org%3A8000&undefined=v1_acquire_slab_001_trimmed_test
        if (args.length == 0) {
            final String[] testArgs = {
                    "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                    "--owner", "hess_wafer_53",
                    "--project", "cut_000_to_009",
                    "--matchCollection", "c000_s095_v01_match",
                    "--stack", "c000_s095_v01",
                    "--targetStack", "c000_s095_v01_align_test_xy_qq",
					"--minX", "0",
					"--maxX", "84000",
					"--minY", "0",
					"--maxY", "86000",
                    "--minZ", "20",
                    "--maxZ", "20",

					"--blockSizeX", "12000",
					"--blockSizeY", "12000",
					// "--blockSizeZ", "100",

                    "--completeTargetStack",
					//"--visualizeResults",

					"--maxNumMatches", "0", // no limit, default
					"--threadsWorker", "1",
					"--threadsGlobal", "12",

                    "--blockOptimizerLambdasRigid",          "1.0,1.0,0.9,0.3,0.01",
                    "--blockOptimizerLambdasTranslation",    "1.0,0.0,0.0,0.0,0.0",
                    "--blockOptimizerLambdasRegularization", "0.0,0.0,0.0,0.0,0.0",
                    "--blockOptimizerIterations", "50,50,30,25,25",
                    "--blockMaxPlateauWidth", "25,25,15,10,10",
                    //"--blockOptimizerIterations", "1000,1000,500,250,250",
                    //"--blockMaxPlateauWidth", "250,250,150,100,100",
					//"--minStitchingInliers", "35",
					//"--stitchFirst", // perform stitch-first
					//"--maxPlateauWidthGlobal", "50",
					//"--maxIterationsGlobal", "10000",
            };
            cmdLineSetup.parse(testArgs);
        } else {
        	cmdLineSetup.parse(args);
        }

		final RenderSetup renderSetup = RenderSetup.setupSolve(cmdLineSetup);

		// Note: different setups can be used if specific things need to be done for the solve or certain blocks
		final DistributedAffineBlockSolver alignmentSolver = new DistributedAffineBlockSolver(cmdLineSetup, renderSetup);

		// create all block instances
		final BlockCollection<?, AffineModel2D, ?> blockCollection =
				alignmentSolver.setupSolve(cmdLineSetup.blockOptimizer.getModel(), cmdLineSetup.stitching.getModel());

		//
		// multi-threaded solve
		//
		LOG.info("Multithreading with thread num=" + cmdLineSetup.distributedSolve.threadsGlobal);

		final ArrayList<Callable<List<BlockData<AffineModel2D, ?>>>> workers = new ArrayList<>();

		blockCollection.allBlocks().forEach(block -> workers.add(() -> createAndRunWorker(block, alignmentSolver, cmdLineSetup)));

		final ArrayList<BlockData<AffineModel2D, ?>> allItems = new ArrayList<>();

		try {
			final ExecutorService taskExecutor = Executors.newFixedThreadPool(cmdLineSetup.distributedSolve.threadsGlobal);

			taskExecutor.invokeAll(workers).forEach(future -> {
				try {
					allItems.addAll(future.get());
				} catch (final InterruptedException | ExecutionException e) {
					LOG.error("Failed to compute alignments: ", e);
				}
			});

			taskExecutor.shutdown();
		} catch (final InterruptedException e) {
			LOG.error("Failed to compute alignments: ", e);
			return;
		}

		// avoid duplicate id assigned while splitting solveitems in the workers
		// but do keep ids that are smaller or equal to the maxId of the initial solveset
		final int maxId = WorkerTools.fixIds(allItems, alignmentSolver.col.maxId());

		LOG.info("main: computed {} blocks, maxId={}", allItems.size(), maxId);

		if (allItems.isEmpty()) {
			throw new IllegalStateException("no blocks were computed, something is wrong");
		}

		final ResultContainer<AffineModel2D> finalTiles = solveAndCombineBlocks(cmdLineSetup, allItems);

		// save the re-aligned part
		LOG.info( "Saving targetstack=" + cmdLineSetup.targetStack );
		final List<Double> zToSave = finalTiles.getIdToTileSpec().values().stream()
				.map(TileSpec::getZ)
				.distinct()
				.sorted()
				.collect(Collectors.toList());

		final RunParameters runParams = new RunParameters();
		runParams.renderDataClient = cmdLineSetup.renderWeb.getDataClient();
		runParams.matchDataClient = cmdLineSetup.matches.getMatchDataClient(cmdLineSetup.renderWeb.baseDataUrl, cmdLineSetup.renderWeb.owner);
		runParams.targetDataClient = cmdLineSetup.renderWeb.getDataClient();
		runParams.pGroupList = null; // not needed below
		runParams.zToGroupIdMap = null; // not needed below
		runParams.sectionIdToZMap = new HashMap<>();
		runParams.zToTileSpecsMap = new HashMap<>();
		runParams.minZ = zToSave.get(0);
		runParams.maxZ = zToSave.get(zToSave.size() - 1);
		LOG.info("Saving from " + runParams.minZ + " to " + runParams.maxZ);

		SolveTools.saveTargetStackTiles(cmdLineSetup.stack, cmdLineSetup.targetStack.stack, runParams, finalTiles.idToModel, null, zToSave, ResolvedTileSpecCollection.TransformApplicationMethod.REPLACE_LAST);
		if (cmdLineSetup.targetStack.completeStack) {
			LOG.info("Completing targetstack=" + cmdLineSetup.targetStack.stack);
			SolveTools.completeStack(cmdLineSetup.targetStack.stack, runParams);
		}
	}

	private static List<BlockData<AffineModel2D, ?>> createAndRunWorker(
			final BlockData<AffineModel2D, ?> block,
			final DistributedAffineBlockSolver alignmentSolver,
			final AffineBlockSolverSetup cmdLineSetup) throws NoninvertibleModelException, IOException, ExecutionException, InterruptedException {

			final Worker<AffineModel2D, ?> worker = block.createWorker(
					alignmentSolver.col.maxId() + 1,
					cmdLineSetup.distributedSolve.threadsWorker);

			worker.run();

			final ArrayList<? extends BlockData<AffineModel2D, ?>> blockDataList = worker.getBlockDataList();
			if (blockDataList == null) {
				throw new IllegalStateException("no items returned for worker " + worker);
			}

			return new ArrayList<>(blockDataList);
	}

	private static ResultContainer<AffineModel2D> solveAndCombineBlocks(
			final AffineBlockSolverSetup cmdLineSetup,
			final ArrayList<BlockData<AffineModel2D, ?>> allItems) {

		final BlockSolver<AffineModel2D, RigidModel2D, AffineModel2D> blockSolver =
				new BlockSolver<>(new RigidModel2D(),
						new SameTileMatchCreatorAffine2D<AffineModel2D>(),
						cmdLineSetup.distributedSolve);

		final BlockCombiner<AffineModel2D, AffineModel2D, RigidModel2D, AffineModel2D> fusion =
				new BlockCombiner<>(
						blockSolver,
						DistributedAffineBlockSolver::integrateGlobalModel,
						DistributedAffineBlockSolver::interpolateModels
				);

		final Assembler<AffineModel2D, RigidModel2D, AffineModel2D> assembler =
				new Assembler<>(
						allItems,
						blockSolver,
						fusion,
						(r) -> {
							final AffineModel2D a = new AffineModel2D();
							a.set(r);
							return a;
						});

		return assembler.createAssembly();
	}


	private static AffineModel2D integrateGlobalModel(final AffineModel2D localModel, final RigidModel2D globalModel) {
		final AffineModel2D fusedModel = new AffineModel2D();
		fusedModel.set(localModel);
		fusedModel.preConcatenate(WorkerTools.createAffine(globalModel));
		return fusedModel;
	}

	private static AffineModel2D interpolateModels(final List<AffineModel2D> models, final List<Double> weights) {
		if (models.isEmpty() || models.size() != weights.size())
			throw new IllegalArgumentException("models and weights must be non-empty and of the same size");

		if (models.size() == 1)
			return models.get(0);

		final AlignmentModelBuilder builder = new AlignmentModelBuilder();
		final Map<String, Double> weightMap = new HashMap<>();
		for (int i = 0; i < models.size(); i++) {
			final String name = "model" + i;
			builder.addModel(name, models.get(i));
			weightMap.put(name, weights.get(i));
		}

		final AlignmentModel model = builder.build();
		model.setWeights(weightMap);
		return model.createAffineModel2D();
	}

	protected <M extends Model<M> & Affine2D<M>, S extends Model<S> & Affine2D<S>>
			BlockCollection<M, AffineModel2D, FIBSEMAlignmentParameters<M, S>> setupSolve(final M blockModel, final S stitchingModel)
	{
		// setup XY BlockFactory
		this.blockFactory = BlockFactory.fromBlocksizes(renderSetup, solverSetup.blockPartition);
		
		// create all blocks
		final BlockCollection<M, AffineModel2D, FIBSEMAlignmentParameters<M, S>> col = setupBlockCollection(this.blockFactory, blockModel, stitchingModel);
		this.col = col;
		return col;
	}

	protected <M extends Model<M> & Affine2D<M>, S extends Model<S> & Affine2D<S>>
			BlockCollection<M, AffineModel2D, FIBSEMAlignmentParameters<M, S>> setupBlockCollection(
					final BlockFactory blockFactory,
					final M blockModel,
					final S stitchingModel) {

		final FIBSEMAlignmentParameters<M, S> defaultSolveParams;
		if (solverSetup.stitchFirst)
			defaultSolveParams = solverSetup.setupSolveParametersWithStitching(blockModel, stitchingModel);
		else
			defaultSolveParams = solverSetup.setupSolveParameters(blockModel, stitchingModel);

		final BlockCollection<M, AffineModel2D, FIBSEMAlignmentParameters<M, S>> bc =
				blockFactory.defineBlockCollection(rtsc -> defaultSolveParams);

		final IntSummaryStatistics stats = bc.allBlocks().stream()
				.mapToInt(block -> block.rtsc().getTileCount())
				.summaryStatistics();

		LOG.info("minTileCount={}, maxTileCount={}, avgTileCount={}", stats.getMin(), stats.getMax(), stats.getAverage());

		return bc;
	}

	private static final Logger LOG = LoggerFactory.getLogger(DistributedAffineBlockSolver.class);
}

package org.janelia.render.client.newsolver;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

import mpicbg.models.RigidModel2D;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.newsolver.assembly.Assembler;
import org.janelia.render.client.newsolver.assembly.AssemblyMaps;
import org.janelia.render.client.newsolver.assembly.BlockCombiner;
import org.janelia.render.client.newsolver.assembly.BlockSolver;
import org.janelia.render.client.newsolver.assembly.matches.SameTileMatchCreatorAffine2D;
import org.janelia.render.client.newsolver.blockfactories.XYBlockFactory;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMAlignmentParameters;
import org.janelia.render.client.newsolver.setup.AffineXYBlockSolverSetup;
import org.janelia.render.client.newsolver.setup.RenderSetup;
import org.janelia.render.client.newsolver.solvers.Worker;
import org.janelia.render.client.newsolver.solvers.WorkerTools;
import org.janelia.render.client.solver.RunParameters;
import org.janelia.render.client.solver.SolveTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.Model;

public class DistributedAffineXYBlockSolver
{
	final AffineXYBlockSolverSetup cmdLineSetup;
	final RenderSetup renderSetup;
	BlockCollection<?, AffineModel2D, ? extends FIBSEMAlignmentParameters<?, ?>> col;
	XYBlockFactory blockFactory;

	public DistributedAffineXYBlockSolver(
			final AffineXYBlockSolverSetup cmdLineSetup,
			final RenderSetup renderSetup )
	{
		this.cmdLineSetup = cmdLineSetup;
		this.renderSetup = renderSetup;
	}

	public static void main( final String[] args ) throws IOException
	{
        final AffineXYBlockSolverSetup cmdLineSetup = new AffineXYBlockSolverSetup();

        // Pointmatch explorer link to the used dataset
        // http://em-services-1.int.janelia.org:8080/render-ws/view/point-match-explorer.html?renderStackOwner=hess&dynamicRenderHost=renderer.int.janelia.org%3A8080&catmaidHost=renderer-catmaid.int.janelia.org%3A8000&matchOwner=hess&renderDataHost=em-services-1.int.janelia.org%3A8080&ndvizHost=renderer.int.janelia.org%3A8080&renderStackProject=wafer_52c&renderStack=v1_acquire_slab_001_trimmed&startZ=1241&endZ=1241&matchCollection=wafer_52c_v4
        // Render stacks
        // http://em-services-1.int.janelia.org:8080/render-ws/view/stacks.html?renderStackOwner=hess&renderStackProject=wafer_52c&dynamicRenderHost=renderer.int.janelia.org%3A8080&catmaidHost=renderer-catmaid.int.janelia.org%3A8000&undefined=v1_acquire_slab_001_trimmed_test
        if (args.length == 0) {
            final String[] testArgs = {
                    "--baseDataUrl", "http://em-services-1.int.janelia.org:8080/render-ws/v1",
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
                    "--maxZ", "21",

					"--blockSizeX", "12000",
					"--blockSizeY", "12000",

                    "--completeTargetStack",
//                    "--visualizeResults",

                    "--blockOptimizerLambdasRigid",          "1.0,1.0,0.9,0.3,0.01",
                    "--blockOptimizerLambdasTranslation",    "1.0,0.0,0.0,0.0,0.0",
                    "--blockOptimizerLambdasRegularization", "0.0,0.0,0.0,0.0,0.0",
                    "--blockOptimizerIterations", "50,50,30,25,25",
                    "--blockMaxPlateauWidth", "25,25,15,10,10",
                    //"--blockOptimizerIterations", "1000,1000,500,250,250",
                    //"--blockMaxPlateauWidth", "250,250,150,100,100",

                    //"--blockSize", "100",
                    //"--minStitchingInliers", "35",
                    //"--stitchFirst", "", // perform stitch-first
                    "--maxNumMatches", "0", // no limit, default
                    "--threadsWorker", "12",
                    "--threadsGlobal", "12",
                    //"--maxPlateauWidthGlobal", "50",
                    //"--maxIterationsGlobal", "10000",
            };
            cmdLineSetup.parse(testArgs);
        } else {
        	cmdLineSetup.parse(args);
        }

		final RenderSetup renderSetup = RenderSetup.setupSolve( cmdLineSetup );

		// Note: different setups can be used if specific things need to be done for the solve or certain blocks
		final DistributedAffineXYBlockSolver solverSetup = new DistributedAffineXYBlockSolver( cmdLineSetup, renderSetup );

		// create all block instances
		final BlockCollection<?, AffineModel2D, ?> blockCollection = solverSetup.setupSolve(cmdLineSetup.blockModel());

		//
		// multi-threaded solve
		//
		LOG.info("Multithreading with thread num=" + cmdLineSetup.distributedSolve.threadsGlobal);

		final ArrayList<Callable<List< BlockData<?, AffineModel2D, ?>>>> workers = new ArrayList<>();

		blockCollection.allBlocks().forEach( block ->
		{
			workers.add( () ->
			{
				final Worker<?, AffineModel2D, ?> worker = block.createWorker(
						solverSetup.col.maxId() + 1,
						cmdLineSetup.distributedSolve.threadsWorker);

				worker.run();

				final ArrayList<? extends BlockData<?, AffineModel2D, ?>> blockDataList = worker.getBlockDataList();
				if (blockDataList == null) {
					throw new IllegalStateException("no items returned for worker " + worker);
				}

				return new ArrayList<>( blockDataList );
			} );
		} );

		final ArrayList< BlockData<?, AffineModel2D, ?> > allItems = new ArrayList<>();

		try {
			final ExecutorService taskExecutor = Executors.newFixedThreadPool(cmdLineSetup.distributedSolve.threadsGlobal);

			taskExecutor.invokeAll( workers ).forEach( future ->
			{
				try {
					allItems.addAll( future.get() );
				} catch (final InterruptedException | ExecutionException e) {
					LOG.error("Failed to compute alignments: ", e);
				}
			} );

			taskExecutor.shutdown();
		} catch (final InterruptedException e) {
			LOG.error("Failed to compute alignments: ", e);
			return;
		}

		// avoid duplicate id assigned while splitting solveitems in the workers
		// but do keep ids that are smaller or equal to the maxId of the initial solveset
		final int maxId = WorkerTools.fixIds( allItems, solverSetup.col.maxId() );

		LOG.info( "computed " + allItems.size() + " blocks, maxId=" + maxId);

		final BlockSolver<AffineModel2D, RigidModel2D, AffineModel2D> blockSolver =
				new BlockSolver<>(
						new RigidModel2D(),
						new SameTileMatchCreatorAffine2D<AffineModel2D>(),
						cmdLineSetup.distributedSolve.maxPlateauWidthGlobal,
						cmdLineSetup.distributedSolve.maxAllowedErrorGlobal,
						cmdLineSetup.distributedSolve.maxIterationsGlobal,
						cmdLineSetup.distributedSolve.threadsGlobal);

		final BlockCombiner<AffineModel2D, AffineModel2D, RigidModel2D, AffineModel2D > fusion =
				new BlockCombiner<>(
						blockSolver,
						DistributedAffineXYBlockSolver::integrateGlobalModel,
						DistributedAffineXYBlockSolver::interpolateModels
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

		final AssemblyMaps<AffineModel2D> finalTiles = assembler.createAssembly();

		// save the re-aligned part
		LOG.info( "Saving targetstack=" + cmdLineSetup.targetStack );
		final List<Double> zToSave = finalTiles.idToTileSpec.values().stream()
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

		// normalize weights
		final double sumWeights = weights.stream().mapToDouble(v -> v).sum();
		final double[] w = weights.stream().mapToDouble(v -> v / sumWeights).toArray();

		final int nCoefficients = 6;
		final double[] c = new double[nCoefficients];
		final double[] cFinal = new double[nCoefficients];

		// extract and interpolate coefficients
		for (int k = 0; k < models.size(); ++k) {
			models.get(k).toArray(c);
			for (int i = 0; i < nCoefficients; ++i)
				cFinal[i] += w[k] * c[i];
		}

		final AffineModel2D interpolatedModel = new AffineModel2D();
		interpolatedModel.set(cFinal[0], cFinal[1], cFinal[2], cFinal[3], cFinal[4], cFinal[5]);
		return interpolatedModel;
	}

	public <M extends Model<M> & Affine2D<M>>
			BlockCollection<M, AffineModel2D, FIBSEMAlignmentParameters<M, M>> setupSolve(final M blockModel)
	{
		//
		// setup XY BlockFactory
		//
		final XYBlockFactory blockFactory = setupBlockFactory();
		
		this.blockFactory = blockFactory;
		
		//
		// create all blocks
		//
		final BlockCollection<M, AffineModel2D, FIBSEMAlignmentParameters<M, M>> col = setupBlockCollection(blockFactory, blockModel);
		this.col = col;
		return col;
	}

	protected <M extends Model<M> & Affine2D<M>>
			BlockCollection<M, AffineModel2D, FIBSEMAlignmentParameters<M, M>> setupBlockCollection(
					final XYBlockFactory blockFactory,
					final M blockModel )
	{
		//
		// setup FIB-SEM solve parameter object
		//
		final FIBSEMAlignmentParameters< M, M > defaultSolveParams =
				setupSolveParameters( blockModel );

		final BlockCollection<M, AffineModel2D, FIBSEMAlignmentParameters<M, M>> bc =
				blockFactory.defineBlockCollection( rtsc -> defaultSolveParams );

		int minTileCount = Integer.MAX_VALUE;
		int maxTileCount = Integer.MIN_VALUE;
		double avgTileCount = 0;
		int count = 0;

		for ( final BlockData<M, AffineModel2D, FIBSEMAlignmentParameters<M, M>> block : bc.allBlocks() )
		{
			final int tc = block.rtsc().getTileCount();

			minTileCount = Math.min( tc, minTileCount );
			maxTileCount = Math.max( tc, maxTileCount );
			avgTileCount += tc;
			++count;
		}

		avgTileCount /= (double)count;

		LOG.info( "minTileCount=" + minTileCount + ", maxTileCount=" + maxTileCount + ", avgTileCount=" + avgTileCount );

		return bc;
	}

	protected XYBlockFactory setupBlockFactory()
	{
		final double minX = renderSetup.minX;
		final double maxX = renderSetup.maxX;
		final double minY = renderSetup.minY;
		final double maxY = renderSetup.maxY;
		final int minZ = (int)Math.round( renderSetup.minZ );
		final int maxZ = (int)Math.round( renderSetup.maxZ );
		final int blockSizeX = cmdLineSetup.blockPartition.sizeX;
		final int blockSizeY = cmdLineSetup.blockPartition.sizeY;

		return new XYBlockFactory(minX, maxX, minY, maxY, minZ, maxZ, blockSizeX, blockSizeY);
	}

	protected < M extends Model< M > & Affine2D< M > > FIBSEMAlignmentParameters< M, M > setupSolveParameters(
			final M blockModel )
	{
		return new FIBSEMAlignmentParameters<>(
				blockModel.copy(),
				(Function< Integer, M > & Serializable )(z) -> blockModel.copy(),
				null, // do not stitch first
				0,//cmdLineSetup.maxAllowedErrorStitching,
				0,//cmdLineSetup.maxIterationsStitching,
				0,//cmdLineSetup.maxPlateauWidthStitching,
				cmdLineSetup.blockOptimizerLambdasRigid,
				cmdLineSetup.blockOptimizerLambdasTranslation,
				cmdLineSetup.blockOptimizerLambdasRegularization,
				cmdLineSetup.blockOptimizerIterations,
				cmdLineSetup.blockMaxPlateauWidth,
				cmdLineSetup.blockMaxAllowedError,
				cmdLineSetup.maxNumMatches,
				cmdLineSetup.maxZRangeMatches,
				cmdLineSetup.preAlign,
				cmdLineSetup.renderWeb.baseDataUrl,
				cmdLineSetup.renderWeb.owner,
				cmdLineSetup.renderWeb.project,
				cmdLineSetup.stack,
				cmdLineSetup.matches.matchOwner,
				cmdLineSetup.matches.matchCollection);
	}

	private static final Logger LOG = LoggerFactory.getLogger(DistributedAffineXYBlockSolver.class);
}

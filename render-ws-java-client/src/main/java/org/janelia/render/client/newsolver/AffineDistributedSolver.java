package org.janelia.render.client.newsolver;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

import org.janelia.render.client.newsolver.blockfactories.ZBlockFactory;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMAlignmentParameters;
import org.janelia.render.client.newsolver.setup.AffineSolverSetup;
import org.janelia.render.client.newsolver.setup.RenderSetup;
import org.janelia.render.client.newsolver.solvers.Worker;
import org.janelia.render.client.newsolver.solvers.affine.AffineAlignBlockWorker;
import org.janelia.render.client.solver.DistributedSolveDeSerialize;
import org.janelia.render.client.solver.DistributedSolveWorker;
import org.janelia.render.client.solver.SolveItemData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.spim.io.IOFunctions;

public class AffineDistributedSolver
{
	final AffineSolverSetup cmdLineSetup;
	final RenderSetup renderSetup;
	BlockCollection< ?, AffineModel2D, ? extends FIBSEMAlignmentParameters< ?, ? >, ZBlockFactory > col;
	ZBlockFactory blockFactory;

	public AffineDistributedSolver(
			final AffineSolverSetup cmdLineSetup,
			final RenderSetup renderSetup )
	{
		this.cmdLineSetup = cmdLineSetup;
		this.renderSetup = renderSetup;
	}

	public static void main( final String[] args ) throws IOException
	{
        final AffineSolverSetup cmdLineSetup = new AffineSolverSetup();

        // TODO: remove testing hack ...
        if (args.length == 0) {
            final String[] testArgs = {
                    "--baseDataUrl", "http://tem-services.int.janelia.org:8080/render-ws/v1",
                    "--owner", "Z0720_07m_BR", //"flyem", //"cosem", //"Z1217_33m_BR",
                    "--project", "Sec24", //"Z0419_25_Alpha3", //"jrc_hela_2", //"Sec10",
                    "--matchCollection", "Sec24_v1", //"Sec32_v1", //"Z0419_25_Alpha3_v1", //"jrc_hela_2_v1", //"Sec10_multi",

                    "--stack", "v5_acquire_trimmed",
                    "--targetStack", "v5_acquire_trimmed_test",
//                    "--minZ", "1234",
                    "--maxZ", "1001",

//                    "--completeTargetStack",
//                    "--visualizeResults",

                    "--blockOptimizerLambdasRigid",          "1.0,1.0,0.9,0.3,0.01",
                    "--blockOptimizerLambdasTranslation",    "1.0,0.0,0.0,0.0,0.0",
                    "--blockOptimizerLambdasRegularization", "0.0,0.0,0.0,0.0,0.0",
                    "--blockOptimizerIterations", "100,100,50,25,25",
                    "--blockMaxPlateauWidth", "25,25,15,10,10",
                    //"--blockOptimizerIterations", "1000,1000,500,250,250",
                    //"--blockMaxPlateauWidth", "250,250,150,100,100",

                    //"--blockSize", "100",
                    //"--minStitchingInliers", "35",
                    //"--stitchFirst", "", // perform stitch-first
                    "--maxNumMatches", "0", // no limit, default
                    "--threadsWorker", "1",
                    //"--threadsGlobal", "60",
                    //"--maxPlateauWidthGlobal", "50",
                    //"--maxIterationsGlobal", "10000",
            };
            cmdLineSetup.parse(testArgs);
        } else {
        	cmdLineSetup.parse(args);
        }

		final RenderSetup renderSetup = RenderSetup.setupSolve( cmdLineSetup );

		// Note: different setups can be used if specific things need to be done for the solve or certain blocks
		final AffineDistributedSolver solverSetup = new AffineDistributedSolver( cmdLineSetup, renderSetup );

		// create all block instances
		final BlockCollection< ?, AffineModel2D, ?, ZBlockFactory > blockCollection =
				solverSetup.setupSolve( cmdLineSetup.blockModel(), cmdLineSetup.stitchingModel() );

		LOG.info( "Multithreading with thread num=" + cmdLineSetup.threadsGlobal );

		final ArrayList< Callable< List< BlockData<?, AffineModel2D, ?, ZBlockFactory> > > > workers = new ArrayList<>();

		for ( final BlockData< ?, AffineModel2D, ?, ZBlockFactory > blockData : blockCollection.allBlocks() )
		{
			workers.add( new Callable<List< BlockData<?, AffineModel2D, ?, ZBlockFactory>>>()
			{
				@Override
				public List< BlockData<?, AffineModel2D, ?, ZBlockFactory> > call() throws Exception
				{
					BlockData<?, AffineModel2D, ?, ZBlockFactory> block1 = blockData;

					final Worker<?, AffineModel2D, ?, ZBlockFactory> worker = block1.createWorker(
							solverSetup.col.maxId() + 1,
							cmdLineSetup.threadsWorker );

					// final Worker<?, AffineModel2D, ?, ZBlockFactory> 
					/*block1.solveTypeParameters().createWorker(
							block1,
							solverSetup.col.maxId() + 1,
							cmdLineSetup.threadsWorker );*/

					//worker.run();
	
					return null;//w.getSolveItemDataList();
				}
			});
		}

		final ArrayList< BlockData<?, AffineModel2D, ?, ZBlockFactory> > allItems  = new ArrayList<>();

		try
		{
			final ExecutorService taskExecutor = Executors.newFixedThreadPool( cmdLineSetup.threadsGlobal );

			// invokeAll() returns when all tasks are complete
			final List< Future< List< BlockData<?, AffineModel2D, ?, ZBlockFactory> > > > futures = taskExecutor.invokeAll( workers );

			taskExecutor.shutdown();

			for ( final Future< List< BlockData<?, AffineModel2D, ?, ZBlockFactory > > > future : futures )
				allItems.addAll( future.get() );
		}
		catch ( final Exception e )
		{
			LOG.error( "Failed to compute alignments: " + e );
			e.printStackTrace();
			return;
		}
		
		final ExecutorService taskExecutor = Executors.newFixedThreadPool( cmdLineSetup.threadsGlobal );
		taskExecutor.submit( () ->
			blockCollection.allBlocks().parallelStream().forEach( block ->
			{
				try
				{
					BlockData<?, AffineModel2D, ?, ZBlockFactory> block1 = block;

					final Worker<?, AffineModel2D, ?, ZBlockFactory> worker = block1.createWorker(
							solverSetup.col.maxId() + 1,
							cmdLineSetup.threadsWorker );

					worker.run();
					ArrayList<?> l = worker.getBlockDataList();
					Object l1 = l.get( 0 );
				}
				catch (IOException | ExecutionException | InterruptedException | NoninvertibleModelException e)
				{
					e.printStackTrace();
					System.exit( 1 );
				}
			}));

		/*
		for ( final Worker<?, ?, ZBlockFactory > worker : workers )
		{
			ArrayList< ? extends BlockData< ?, AffineModel2D, ?, ? > > blockData = worker.getBlockDataList();
		}

		// avoid duplicate id assigned while splitting solveitems in the workers
		// but do keep ids that are smaller or equal to the maxId of the initial solveset
		final int maxId = SolveTools.fixIds( this.allItems, solverSetup.col.maxId() );

		System.out.println( workers.get( 0 ).getBlockDataList().size() );
		*/
	}

	public < M extends Model< M > & Affine2D< M >, S extends Model< S > & Affine2D< S > >
			BlockCollection< M, AffineModel2D, FIBSEMAlignmentParameters< M, S >, ZBlockFactory > setupSolve(
			final M blockModel,
			final S stitchingModel )
	{
		//
		// setup Z BlockFactory
		//
		final ZBlockFactory blockFactory = setupBlockFactory();

		this.blockFactory = blockFactory;

		//
		// create all blocks
		//
		final BlockCollection< M, AffineModel2D, FIBSEMAlignmentParameters< M, S >, ZBlockFactory > col =
				setupBlockCollection( blockFactory, blockModel, stitchingModel );

		this.col = col;

		return col;
	}

	protected ZBlockFactory setupBlockFactory()
	{
		final int minZ = (int)Math.round( renderSetup.minZ );
		final int maxZ = (int)Math.round( renderSetup.maxZ );
		final int blockSize = cmdLineSetup.blockSize;
		final int minBlockSize = cmdLineSetup.minBlockSize;

		return new ZBlockFactory( minZ, maxZ, blockSize, minBlockSize );
	}

	protected < M extends Model< M > & Affine2D< M >, S extends Model< S > & Affine2D< S > > FIBSEMAlignmentParameters< M, S > setupSolveParameters(
			final M blockModel,
			final S stitchingModel )
	{
		final boolean stitchFirst = cmdLineSetup.stitchFirst;

		final FIBSEMAlignmentParameters< M, S > solveParams = new FIBSEMAlignmentParameters< M, S >(
				blockModel,
				(Function< Integer,S > & Serializable )(z) -> stitchingModel,
				stitchFirst ? (Function< Integer, Integer > & Serializable )(z) -> cmdLineSetup.minStitchingInliers : null,
				cmdLineSetup.maxAllowedErrorStitching,
				cmdLineSetup.maxIterationsStitching,
				cmdLineSetup.maxPlateauWidthStitching,
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
				cmdLineSetup.matchOwner,
				cmdLineSetup.matchCollection );

		return solveParams;
	}

	protected < M extends Model< M > & Affine2D< M >, S extends Model< S > & Affine2D< S > > BlockCollection< M, AffineModel2D, FIBSEMAlignmentParameters< M, S >, ZBlockFactory > setupBlockCollection(
			final ZBlockFactory blockFactory,
			final M blockModel,
			final S stitchingModel )
	{
		//
		// setup FIB-SEM solve parameter object
		//
		final FIBSEMAlignmentParameters< M, S > defaultSolveParams =
				setupSolveParameters( blockModel, stitchingModel );

		final BlockCollection< M, AffineModel2D, FIBSEMAlignmentParameters< M, S >, ZBlockFactory > col =
				blockFactory.defineBlockCollection( rtsc -> defaultSolveParams );

		return col;
	}

	protected < M extends Model< M > & Affine2D< M >, S extends Model< S > & Affine2D< S > > ArrayList< AffineAlignBlockWorker<M, S, ZBlockFactory > > createWorkers(
			final BlockCollection< M, AffineModel2D, FIBSEMAlignmentParameters< M, S >, ZBlockFactory > col )
	{
		final ArrayList< AffineAlignBlockWorker<M, S, ZBlockFactory > > workers = new ArrayList<>();

		for ( final BlockData< M, AffineModel2D, FIBSEMAlignmentParameters< M, S >, ZBlockFactory > block : col.allBlocks() )
		{
			workers.add( new AffineAlignBlockWorker<>( block, col.maxId() + 1, cmdLineSetup.threadsWorker ) );
		}

		return workers;
	}

	private static final Logger LOG = LoggerFactory.getLogger(AffineDistributedSolver.class);
}

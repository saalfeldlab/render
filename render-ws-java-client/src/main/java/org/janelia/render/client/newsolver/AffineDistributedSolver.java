package org.janelia.render.client.newsolver;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.janelia.render.client.newsolver.assembly.Assembler;
import org.janelia.render.client.newsolver.assembly.ZBlockFusion;
import org.janelia.render.client.newsolver.assembly.ZBlockSolver;
import org.janelia.render.client.newsolver.assembly.matches.SameTileMatchCreatorAffine2D;
import org.janelia.render.client.newsolver.blockfactories.ZBlockFactory;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMAlignmentParameters;
import org.janelia.render.client.newsolver.setup.AffineSolverSetup;
import org.janelia.render.client.newsolver.setup.RenderSetup;
import org.janelia.render.client.newsolver.solvers.Worker;
import org.janelia.render.client.newsolver.solvers.WorkerTools;
import org.janelia.render.client.newsolver.solvers.affine.AffineAlignBlockWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.RigidModel2D;

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
                    "--blockOptimizerIterations", "50,50,30,25,25",
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

		//
		// multi-threaded solve
		//
		LOG.info( "Multithreading with thread num=" + cmdLineSetup.threadsGlobal );

		final ArrayList< Callable< List< BlockData<?, AffineModel2D, ?, ZBlockFactory> > > > workers = new ArrayList<>();

		blockCollection.allBlocks().forEach( block ->
		{
			workers.add( () ->
			{
				final Worker<?, AffineModel2D, ?, ZBlockFactory> worker = block.createWorker(
						solverSetup.col.maxId() + 1,
						cmdLineSetup.threadsWorker );

				worker.run();

				return new ArrayList<>( worker.getBlockDataList() );
			} );
		} );

		final ArrayList< BlockData<?, AffineModel2D, ?, ZBlockFactory> > allItems = new ArrayList<>();

		try
		{
			final ExecutorService taskExecutor = Executors.newFixedThreadPool( cmdLineSetup.threadsGlobal );

			taskExecutor.invokeAll( workers ).forEach( future ->
			{
				try
				{
					allItems.addAll( future.get() );
				}
				catch (InterruptedException | ExecutionException e)
				{
					LOG.error( "Failed to compute alignments: " + e );
					e.printStackTrace();
					return;
				}
			} );

			taskExecutor.shutdown();
		}
		catch (InterruptedException e1)
		{
			LOG.error( "Failed to compute alignments: " + e1 );
			e1.printStackTrace();
			return;
		}

		// avoid duplicate id assigned while splitting solveitems in the workers
		// but do keep ids that are smaller or equal to the maxId of the initial solveset
		final int maxId = WorkerTools.fixIds( allItems, solverSetup.col.maxId() );

		LOG.info( "computed " + allItems.size() + " blocks, maxId=" + maxId);

		final ZBlockSolver< AffineModel2D, RigidModel2D, AffineModel2D > solver =
				new ZBlockSolver<>(
						new RigidModel2D(),
						new SameTileMatchCreatorAffine2D<AffineModel2D>(),
						cmdLineSetup.distributedSolve.maxPlateauWidthGlobal,
						cmdLineSetup.distributedSolve.maxAllowedErrorGlobal,
						cmdLineSetup.distributedSolve.maxIterationsGlobal,
						cmdLineSetup.threadsGlobal );

		final ZBlockFusion<AffineModel2D, AffineModel2D, RigidModel2D, AffineModel2D > fusion =
				new ZBlockFusion<>(
						solver,
						(r,g) -> { return null; }, // TODO: BiFunction< R, G, I > combineResultGlobal, // I is some intermediate (maybe R, maybe something else)
						(i,w) -> { return null; } ); // TODO: BiFunction< List< I >, List< Double >, Z > fusion ) // then fuse many weighted I's into Z's

		final Assembler< AffineModel2D, RigidModel2D, AffineModel2D, ZBlockFactory > assembler =
				new Assembler<>(
						allItems,
						solver,
						(r,z) -> z.set( r ),
						() -> new AffineModel2D() );

		assembler.createAssembly();

		// TODO: interface to interpolate many R's into a Z given the weights - should support trivial case of 1 single R to Z

		/*
		//
		// Saving the result
		//
		LOG.info( "Saving targetstack=" + cmdLineSetup.targetStack );

		//
		// save the re-aligned part
		//
		final HashSet< Double > zToSaveSet = new HashSet<>();

		for ( final TileSpec ts : solve.idToTileSpecGlobal.values() )
			zToSaveSet.add( ts.getZ() );

		List< Double > zToSave = new ArrayList<>( zToSaveSet );
		Collections.sort( zToSave );

		LOG.info("Saving from " + zToSave.get( 0 ) + " to " + zToSave.get( zToSave.size() - 1 ) );

		SolveTools.saveTargetStackTiles( parameters.stack, parameters.targetStack, runParams, solve.idToFinalModelGlobal, null, zToSave, TransformApplicationMethod.REPLACE_LAST );

		if ( parameters.completeTargetStack )
		{
			LOG.info( "Completing targetstack=" + parameters.targetStack );

			SolveTools.completeStack( parameters.targetStack, runParams );
		}
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
		final int blockSize = cmdLineSetup.distributedSolve.blockSize;
		final int minBlockSize = cmdLineSetup.distributedSolve.minBlockSize;

		return new ZBlockFactory( minZ, maxZ, blockSize, minBlockSize );
	}

	protected < M extends Model< M > & Affine2D< M >, S extends Model< S > & Affine2D< S > > FIBSEMAlignmentParameters< M, S > setupSolveParameters(
			final M blockModel,
			final S stitchingModel )
	{
		final boolean stitchFirst = cmdLineSetup.stitchFirst;

		return new FIBSEMAlignmentParameters<>(
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

		return blockFactory.defineBlockCollection(rtsc -> defaultSolveParams );
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

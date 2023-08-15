package org.janelia.render.client.newsolver;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import org.janelia.render.client.newsolver.blockfactories.ZBlockFactory;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMAlignmentParameters;
import org.janelia.render.client.newsolver.setup.AffineSolverSetup;
import org.janelia.render.client.newsolver.setup.RenderSetup;
import org.janelia.render.client.newsolver.solvers.affine.AffineAlignBlockWorker;

import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.NoninvertibleModelException;

public class AffineDistributedSolver
{
	final AffineSolverSetup cmdLineSetup;
	final RenderSetup renderSetup;

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
                    "--maxZ", "3001",

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

		final ArrayList< ? extends AffineAlignBlockWorker<?, ?, ZBlockFactory > > workers =
				solverSetup.setupSolve( cmdLineSetup.blockModel(), cmdLineSetup.stitchingModel() );

		try
		{
			workers.get( 0 ).run();
			System.out.println( workers.get( 0 ).getBlockDataList().size() );
			
		} catch (IOException | ExecutionException | InterruptedException | NoninvertibleModelException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public < M extends Model< M > & Affine2D< M >, S extends Model< S > & Affine2D< S > >
			ArrayList< AffineAlignBlockWorker<M, S, ZBlockFactory > > setupSolve(
			final M blockModel,
			final S stitchingModel )
	{
		//
		// setup Z BlockFactory
		//
		final ZBlockFactory blockFactory = setupBlockFactory();

		//
		// setup FIB-SEM solve parameters
		//
		final FIBSEMAlignmentParameters< M, S > solveParams =
				setupSolveParameters( blockModel, stitchingModel );

		//
		// create all blocks
		//
		final BlockCollection< M, AffineModel2D, FIBSEMAlignmentParameters< M, S >, ZBlockFactory > col =
				setupBlockCollection( blockFactory, solveParams );

		//
		// create workers
		//
		return createWorkers( col );
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
			final FIBSEMAlignmentParameters< M, S > solveParams )
	{
		final BlockCollection< M, AffineModel2D, FIBSEMAlignmentParameters< M, S >, ZBlockFactory > col =
				blockFactory.defineBlockCollection( solveParams );

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
}

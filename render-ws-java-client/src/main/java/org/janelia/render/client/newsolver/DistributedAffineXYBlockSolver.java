package org.janelia.render.client.newsolver;

import java.io.IOException;
import java.io.Serializable;
import java.util.function.Function;

import org.janelia.render.client.newsolver.blockfactories.XYBlockFactory;
import org.janelia.render.client.newsolver.blockfactories.ZBlockFactory;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMAlignmentParameters;
import org.janelia.render.client.newsolver.setup.AffineXYBlockSolverSetup;
import org.janelia.render.client.newsolver.setup.RenderSetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.Model;

public class DistributedAffineXYBlockSolver
{
	final AffineXYBlockSolverSetup cmdLineSetup;
	final RenderSetup renderSetup;
	BlockCollection< ?, AffineModel2D, ? extends FIBSEMAlignmentParameters< ?, ? >, XYBlockFactory > col;
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

        // TODO: remove testing hack ...
        if (args.length == 0) {
            final String[] testArgs = {
                    //"--baseDataUrl", "http://tem-services.int.janelia.org:8080/render-ws/v1",
                    "--baseDataUrl", "http://em-services-1.int.janelia.org:8080/render-ws/v1",
                    "--owner", "hess",
                    "--project", "wafer_52c",
                    "--matchCollection", "wafer_52c_v4",
                    "--stack", "v1_acquire_slab_001_trimmed",
                    "--targetStack", "	v1_acquire_slab_001_trimmed_test",
//                    "--minZ", "1225",
//                    "--maxZ", "1246",

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
                    "--threadsGlobal", "60",
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
		final BlockCollection< ?, AffineModel2D, ?, XYBlockFactory > blockCollection =
				solverSetup.setupSolve( cmdLineSetup.blockModel() );

		//
		// multi-threaded solve
		//
		LOG.info("Multithreading with thread num=" + cmdLineSetup.distributedSolve.threadsGlobal);

		// TODO ...
	}

	public < M extends Model< M > & Affine2D< M > >
			BlockCollection< M, AffineModel2D, FIBSEMAlignmentParameters< M, M >, XYBlockFactory > setupSolve(
					final M blockModel )
	{
		//
		// setup XY BlockFactory
		//
		final XYBlockFactory blockFactory = setupBlockFactory();
		
		this.blockFactory = blockFactory;
		
		//
		// create all blocks
		//
		final BlockCollection< M, AffineModel2D, FIBSEMAlignmentParameters< M, M >, XYBlockFactory > col =
				setupBlockCollection( blockFactory, blockModel );
		
		this.col = col;
		
		return col;
	}

	protected < M extends Model< M > & Affine2D< M > >
			BlockCollection< M, AffineModel2D, FIBSEMAlignmentParameters< M, M >, XYBlockFactory > setupBlockCollection(
					final XYBlockFactory blockFactory,
					final M blockModel )
	{
		//
		// setup FIB-SEM solve parameter object
		//
		final FIBSEMAlignmentParameters< M, M > defaultSolveParams =
				setupSolveParameters( blockModel );

		return blockFactory.defineBlockCollection(rtsc -> defaultSolveParams );
	}

	protected XYBlockFactory setupBlockFactory()
	{
		final double minX = renderSetup.minX;
		final double maxX = renderSetup.maxX;
		final double minY = renderSetup.minY;
		final double maxY = renderSetup.maxY;
		final int minZ = (int)Math.round( renderSetup.minZ );
		final int maxZ = (int)Math.round( renderSetup.maxZ );
		final int blockSizeX = cmdLineSetup.distributedSolve.blockSizeX;
		final int minBlockSizeX = cmdLineSetup.distributedSolve.minBlockSizeX;
		final int blockSizeY = cmdLineSetup.distributedSolve.blockSizeY;
		final int minBlockSizeY = cmdLineSetup.distributedSolve.minBlockSizeY;

		return new XYBlockFactory( minX, maxX, minY, maxY, minZ, maxZ, blockSizeX, blockSizeY, minBlockSizeX, minBlockSizeY );
	}

	protected < M extends Model< M > & Affine2D< M > > FIBSEMAlignmentParameters< M, M > setupSolveParameters(
			final M blockModel )
	{
		final boolean stitchFirst = cmdLineSetup.stitchFirst;

		return new FIBSEMAlignmentParameters<>(
				blockModel.copy(),
				(Function< Integer, M > & Serializable )(z) -> blockModel.copy(),
				(Function< Integer, Integer > & Serializable )(z) -> null,
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

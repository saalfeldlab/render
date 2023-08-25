package org.janelia.render.client.newsolver;

import java.io.IOException;

import org.janelia.render.client.newsolver.blockfactories.XYBlockFactory;
import org.janelia.render.client.newsolver.blockfactories.ZBlockFactory;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMAlignmentParameters;
import org.janelia.render.client.newsolver.setup.AffineZBlockSolverSetup;
import org.janelia.render.client.newsolver.setup.RenderSetup;

import mpicbg.models.AffineModel2D;

public class DistributedAffineXYBlockSolver
{
	final AffineZBlockSolverSetup cmdLineSetup;
	final RenderSetup renderSetup;
	BlockCollection< ?, AffineModel2D, ? extends FIBSEMAlignmentParameters< ?, ? >, ZBlockFactory > col;
	XYBlockFactory blockFactory;

	public DistributedAffineXYBlockSolver(
			final AffineZBlockSolverSetup cmdLineSetup,
			final RenderSetup renderSetup )
	{
		this.cmdLineSetup = cmdLineSetup;
		this.renderSetup = renderSetup;
	}

	public static void main( final String[] args ) throws IOException
	{
        final AffineZBlockSolverSetup cmdLineSetup = new AffineZBlockSolverSetup();

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
	}

	protected XYBlockFactory setupBlockFactory()
	{
		/*
		final int minX = (int)Math.round( renderSetup.minX );
		final int maxX = (int)Math.round( renderSetup.maxX );
		final int minY = (int)Math.round( renderSetup.minY );
		final int maxY = (int)Math.round( renderSetup.maxY );*/
		final int minZ = (int)Math.round( renderSetup.minZ );
		final int maxZ = (int)Math.round( renderSetup.maxZ );
		final int blockSize = cmdLineSetup.distributedSolve.blockSize;
		final int minBlockSize = cmdLineSetup.distributedSolve.minBlockSize;

		return null;//new XYBlockFactory( minZ, maxZ, blockSize, minBlockSize );
	}

	
}

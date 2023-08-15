package org.janelia.render.client.newsolver;

import java.io.IOException;
import java.io.Serializable;
import java.util.function.Function;

import org.janelia.render.client.newsolver.blockfactories.BlockFactory;
import org.janelia.render.client.newsolver.blockfactories.ZBlockFactory;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMAlignmentParameters;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMAlignmentParameters.PreAlign;
import org.janelia.render.client.newsolver.setup.AffineSolverSetup;
import org.janelia.render.client.newsolver.setup.RenderSetup;
import org.janelia.render.client.solver.DistributedSolveParameters;
import org.janelia.render.client.solver.RunParameters;

import mpicbg.models.Affine2D;

public class AffineDistributedSolver
{
	public static void main( final String[] args ) throws IOException
	{
        final AffineSolverSetup setupParameters = new AffineSolverSetup();

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
//                    "--maxZ", "1234",

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
                    "--minStitchingInliers", "100000000",// do not stitch first
                    "--maxNumMatches", "0", // no limit, default
                    "--threadsWorker", "1", 
                    "--threadsGlobal", "60",
                    "--maxPlateauWidthGlobal", "50",
                    "--maxIterationsGlobal", "10000",
            };
            setupParameters.parse(testArgs);
        } else {
        	setupParameters.parse(args);
        }

		final RenderSetup renderSetup = AffineSolverSetup.setupSolve( setupParameters );

		//
		// setup Z BlockFactory
		//
		final int minZ = (int)Math.round( runParameters.minZ );
		final int maxZ = 5000;//(int)Math.round( runParameters.maxZ );
		final int blockSize = parameters.blockSize;
		final int minBlockSize = parameters.minBlockSize;

		final BlockFactory< ZBlockFactory > blockFactory = new ZBlockFactory( minZ, maxZ, blockSize, minBlockSize );
		/*
		//
		// setup FIB-SEM solve parameters
		//
		final PreAlign preAlign = PreAlign.NONE;

		FIBSEMAlignmentParameters solveParams = new FIBSEMAlignmentParameters(
				parameters.blockModel(),
				(Function< Integer, Affine2D<?> > & Serializable )(z) -> parameters.stitchingModel(),
				(Function< Integer, Integer > & Serializable )(z) -> parameters.minStitchingInliers,
				parameters.blockOptimizerLambdasRigid,
				parameters.blockOptimizerLambdasTranslation,
				parameters.blockOptimizerIterations,
				parameters.blockMaxPlateauWidth,
				parameters.blockMaxAllowedError,
				preAlign,
				parameters.renderWeb.baseDataUrl,
				parameters.renderWeb.owner,
				parameters.renderWeb.project,
				parameters.stack );

		//
		// create all blocks
		//
		BlockCollection col = blockFactory.defineBlockCollection( solveParams );
		*/
	}
}

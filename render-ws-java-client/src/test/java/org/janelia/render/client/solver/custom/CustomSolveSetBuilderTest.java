package org.janelia.render.client.solver.custom;

import org.janelia.render.client.solver.DistributedSolveParameters;
import org.janelia.render.client.solver.SolveSetFactory;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link CustomSolveSetBuilder} class.
 *
 * @author Eric Trautman
 */
public class CustomSolveSetBuilderTest {

    @Test
    public void testBuild() {

        final String args =
                "--baseDataUrl http://renderer-dev/render-ws/v1 --owner o --project p --stack s --targetStack ts " +
                "--matchCollection mc --maxNumMatches 0 --completeTargetStack " +
                "--blockSize 500 --blockOptimizerLambdasRigid 1.0,1.0,0.9,0.3,0.01 " +
                "--blockOptimizerLambdasTranslation 1.0,0.0,0.0,0.0,0.0 " +
                "--blockOptimizerIterations 1000,1000,500,250,250 --blockMaxPlateauWidth 250,250,150,100,100 " +
                "--maxPlateauWidthGlobal 50 --maxIterationsGlobal 10000 --dynamicLambdaFactor 0.0 " +
                "--threadsWorker 1 --threadsGlobal 1 " +
                "--customSolveClass org.janelia.render.client.solver.custom.SolveSetFactoryBRSec36";

        final DistributedSolveParameters parameters = new DistributedSolveParameters();
        parameters.parse(args.split(" "));

        final SolveSetFactory solveSetFactory = CustomSolveSetBuilder.build(parameters.customSolveClass,
                                                                            parameters.globalModel(),
                                                                            parameters.blockModel(),
                                                                            parameters.stitchingModel(),
                                                                            parameters.blockOptimizerLambdasRigid,
                                                                            parameters.blockOptimizerLambdasTranslation,
                                                                            parameters.blockOptimizerIterations,
                                                                            parameters.blockMaxPlateauWidth,
                                                                            parameters.minStitchingInliers,
                                                                            parameters.blockMaxAllowedError,
                                                                            parameters.dynamicLambdaFactor);

        Assert.assertTrue("", solveSetFactory instanceof SolveSetFactoryBRSec36);
    }

}

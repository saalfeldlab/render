package org.janelia.render.client.solver;

import org.apache.spark.SparkConf;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests the {@link DistributedSolveSpark} class.
 */
public class DistributedSolveSparkTest {

    @Test
    public void testParameterParsing() {
        CommandLineParameters.parseHelp(new DistributedSolveParameters());
    }

    public static void main(final String[] args) {

        final int numberOfConcurrentTasks = 1;

        final String[] testArgs = args.length > 0 ? args : new String[] {
                "--baseDataUrl", "http://tem-services.int.janelia.org:8080/render-ws/v1",
                "--owner", "Z1217_19m",
                "--project", "Sec08",
                "--matchCollection", "Sec08_patch_matt",
                "--stack", "v2_py_solve_03_affine_e10_e10_trakem2_22103_15758",
                //"--targetStack", "v2_py_solve_03_affine_e10_e10_trakem2_22103_15758_new",
//                "--completeTargetStack",

                "--blockOptimizerLambdasRigid", "1.0,0.5,0.1,0.01",
                "--blockOptimizerLambdasTranslation", "0.0,0.0,0.0,0.0",
                "--blockOptimizerIterations", "100,100,40,20",
                "--blockMaxPlateauWidth", "50,50,50,50",

                "--blockSize", "100",
                //"--noStitching", // do not stitch first

                "--minZ", "10000",
                "--maxZ", "10199"
        };


        final DistributedSolveParameters parameters = new DistributedSolveParameters();
        parameters.parse(testArgs);

        // TODO: change this to a parameter and remove from global scope
        DistributedSolve.visualizeOutput = false;

        LOG.info("main: parameters={}", parameters);

        try {

            final String master = "local[" + numberOfConcurrentTasks + "]";
            final SparkConf sparkConf = new SparkConf()
                    .setMaster(master)
                    .setAppName(DistributedSolveSpark.class.getSimpleName());

            final SolveSetFactory solveSetFactory =
    		new SolveSetFactorySimple(
    				parameters.globalModel(),
    				parameters.blockModel(),
    				parameters.stitchingModel(),
    				parameters.blockOptimizerLambdasRigid,
    				parameters.blockOptimizerLambdasTranslation,
    				parameters.blockOptimizerIterations,
    				parameters.blockMaxPlateauWidth,
    				parameters.blockMaxAllowedError,
    				parameters.dynamicLambdaFactor );

            final DistributedSolve client = new DistributedSolveSpark(solveSetFactory, parameters, sparkConf);

            client.run();

        } catch (final Throwable t) {
            LOG.error("caught exception", t);
        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(DistributedSolveSparkTest.class);

}

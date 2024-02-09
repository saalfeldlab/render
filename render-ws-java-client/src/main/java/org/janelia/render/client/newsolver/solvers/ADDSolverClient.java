package org.janelia.render.client.newsolver.solvers;

import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.newsolver.DistributedAffineBlockSolver;
import org.janelia.render.client.newsolver.AlternatingSolveUtils;
import org.janelia.render.client.newsolver.setup.AffineBlockSolverSetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Alternating Domain Decomposition solver:
 * A solver that alternates between solving for a given block layout and a shifted one so that
 * all tiles are placed in the center of a block in one of the iterations.
 * See also: additive Schwarz domain decomposition.
 *
 * @author Michael Innerberger
 */
public class ADDSolverClient {

	public static void main(String[] args) {
		if (args.length == 0) {
			args = new String[]{"--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
					"--owner", "hess_wafer_53",
					"--project", "cut_000_to_009",
					"--matchCollection", "c009_s310_v01_match",
					"--stack", "c009_s310_v01_mfov_08",
					"--targetStack", "c009_s310_v01_mfov_08_test",
					"--minX", "33400",
					"--maxX", "54600",
					"--minY", "400",
					"--maxY", "18700",
					"--minZ", "424",
					"--maxZ", "460",

					"--blockSizeX", "7000",
					"--blockSizeY", "6000",

					"--completeTargetStack",

					"--maxNumMatches", "0", // no limit, default
					"--threadsWorker", "1",
					"--threadsGlobal", "5",

					"--blockOptimizerLambdasRigid", "1.0,1.0,0.9,0.3,0.01",
					"--blockOptimizerLambdasTranslation", "1.0,0.0,0.0,0.0,0.0",
					"--blockOptimizerLambdasRegularization", "0.0,0.0,0.0,0.0,0.0",
					"--blockOptimizerIterations", "50,50,30,25,25",
					"--blockMaxPlateauWidth", "25,25,15,10,10",

					"--alternatingRuns", "4"
			};
		}

		final ClientRunner clientRunner = new ClientRunner(args) {
			@Override
			public void runClient(final String[] args) throws Exception {

				final AffineBlockSolverSetup parameters = new AffineBlockSolverSetup();
				parameters.parse(args);

				LOG.info("runClient: entry, parameters={}", parameters);

				final ADDSolverClient client = new ADDSolverClient();

				client.solveAlternating(parameters);
			}
		};
		clientRunner.run();
	}

	private void solveAlternating(final AffineBlockSolverSetup parameters) throws IOException, InterruptedException {
		final String targetStackName = parameters.targetStack.stack;
		final int nRuns = parameters.alternatingRuns.nRuns;

		for (int runNumber = 1; runNumber <= nRuns; runNumber++) {
			LOG.info("solveAlternating: run {} of {}; parameters={}", runNumber, nRuns, parameters);

			parameters.targetStack.stack = AlternatingSolveUtils.getStackNameForRun(targetStackName, runNumber, nRuns);
			DistributedAffineBlockSolver.run(parameters);

			if ((! parameters.alternatingRuns.keepIntermediateStacks) && (runNumber > 1)) {
				AlternatingSolveUtils.cleanUpIntermediateStack(parameters.renderWeb, parameters.stack);
			}

			AlternatingSolveUtils.updateParametersForNextRun(parameters, runNumber);
			parameters.stack = parameters.targetStack.stack;
		}
	}


	private static final Logger LOG = LoggerFactory.getLogger(ADDSolverClient.class);
}

package org.janelia.render.client.newsolver.solvers;

import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.newsolver.DistributedAffineBlockSolver;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMAlignmentParameters.PreAlign;
import org.janelia.render.client.newsolver.setup.AffineBlockSolverSetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * A solver that alternates between solving for a given block layout and a shifted one so that
 * all tiles are placed in the center of a block in one of the iterations.
 * See also: additive Schwarz domain decomposition.
 *
 * @author Michael Innerberger
 */
public class AlternatingDomainDecompositionSolverClient {

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

				final AlternatingDomainDecompositionSolverClient client =
						new AlternatingDomainDecompositionSolverClient();

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

			parameters.targetStack.stack = getStackName(targetStackName, runNumber, nRuns);
			DistributedAffineBlockSolver.run(parameters);

			if ((! parameters.alternatingRuns.keepIntermediateStacks) && (runNumber > 1))
				cleanUpIntermediateStack(parameters);

			updateParameters(parameters);
		}
	}

	private static String getStackName(final String name, final int runNumber, final int nTotalRuns) {
		if (runNumber == nTotalRuns) {
			return name;
		} else {
			return name + "_run" + runNumber;
		}
	}

	private static void cleanUpIntermediateStack(final AffineBlockSolverSetup parameters) {
		final RenderDataClient dataClient = parameters.renderWeb.getDataClient();
		try {
			dataClient.deleteStack(parameters.stack, null);
			LOG.info("cleanUpIntermediateStack: deleted stack {}", parameters.stack);
		} catch (final IOException e) {
			LOG.error("cleanUpIntermediateStack: error deleting stack {}", parameters.stack, e);
		}
	}

	private static void updateParameters(final AffineBlockSolverSetup parameters) {
		// alternate block layout
		parameters.blockPartition.shiftBlocks = !parameters.blockPartition.shiftBlocks;

		// get data from previous run
		parameters.stack = parameters.targetStack.stack;

		// don't stitch or pre-align after first run
		parameters.stitchFirst = false;
		parameters.preAlign = PreAlign.NONE;

		// fix tile boundaries after the initial run
		parameters.blockOptimizer.fixBlockBoundary = true;
	}

	private static final Logger LOG = LoggerFactory.getLogger(AlternatingDomainDecompositionSolverClient.class);
}

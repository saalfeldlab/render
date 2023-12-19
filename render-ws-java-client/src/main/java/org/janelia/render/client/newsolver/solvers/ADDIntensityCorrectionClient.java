package org.janelia.render.client.newsolver.solvers;

import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.newsolver.DistributedIntensityCorrectionSolver;
import org.janelia.render.client.newsolver.setup.IntensityCorrectionSetup;
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
public class ADDIntensityCorrectionClient {

	public static void main(String[] args) {
		if (args.length == 0) {
			args = new String[]{"--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
					"--owner", "hess_wafer_53",
					"--project", "cut_000_to_009",
					"--stack", "c009_s310_v01_mfov_08_test",
					"--targetStack", "c009_s310_v01_mfov_08_test_ic_4",
					"--minX", "33400",
					"--maxX", "36600",
					"--minY", "8300",
					"--maxY", "8500",
					"--minZ", "424",
					"--maxZ", "425",

					"--blockSizeX", "7000",
					"--blockSizeY", "6000",

					"--completeTargetStack",

					"--threadsWorker", "2",
					"--threadsGlobal", "5",

					"--zDistance", "0",

					"--alternatingRuns", "1"
			};
		}

		final ClientRunner clientRunner = new ClientRunner(args) {
			@Override
			public void runClient(final String[] args) throws Exception {

				final IntensityCorrectionSetup parameters = new IntensityCorrectionSetup();
				parameters.parse(args);

				LOG.info("runClient: entry, parameters={}", parameters);

				final ADDIntensityCorrectionClient client = new ADDIntensityCorrectionClient();

				client.solveAlternating(parameters);
			}
		};
		clientRunner.run();
	}

	private void solveAlternating(final IntensityCorrectionSetup parameters) throws IOException {
		final String targetStackName = parameters.targetStack.stack;
		final int nRuns = parameters.alternatingRuns.nRuns;

		for (int runNumber = 1; runNumber <= nRuns; runNumber++) {
			LOG.info("solveAlternating: run {} of {}; parameters={}", runNumber, nRuns, parameters);

			parameters.targetStack.stack = getStackName(targetStackName, runNumber, nRuns);
			DistributedIntensityCorrectionSolver.run(parameters);

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

	private static void cleanUpIntermediateStack(final IntensityCorrectionSetup parameters) {
		final RenderDataClient dataClient = parameters.renderWeb.getDataClient();
		final String stackToDelete = parameters.intensityAdjust.stack;
		try {
			dataClient.deleteStack(stackToDelete, null);
			LOG.info("cleanUpIntermediateStack: deleted stack {}", stackToDelete);
		} catch (final IOException e) {
			LOG.error("cleanUpIntermediateStack: error deleting stack {}", stackToDelete, e);
		}
	}

	private static void updateParameters(final IntensityCorrectionSetup parameters) {
		// alternate block layout
		parameters.blockPartition.shiftBlocks = !parameters.blockPartition.shiftBlocks;

		// get data from previous run
		parameters.intensityAdjust.stack = parameters.targetStack.stack;
	}

	private static final Logger LOG = LoggerFactory.getLogger(ADDIntensityCorrectionClient.class);
}

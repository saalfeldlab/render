package org.janelia.render.client.spark.newsolver;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.newsolver.setup.DistributedSolveParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

public abstract class DistributedAlternatingDomainDecompositionSolver {

	protected static int deriveParallelismValues(final JavaSparkContext sparkContext,
												 final List<DistributedSolveParameters> solveParameters) {

		// From https://spark.apache.org/docs/3.4.1/configuration.html#execution-behavior ...
		//   For these cluster managers, spark.default.parallelism is:
		//   - Local mode: number of cores on the local machine
		//   - Mesos fine grained mode: 8
		//   - Others: total number of cores on all executor nodes or 2, whichever is larger.
		int parallelism = sparkContext.defaultParallelism();
		final DistributedSolveParameters firstSolveParameters = solveParameters.get(0);

		LOG.info("deriveParallelismValues: entry, threadsGlobal={}, threadsWorker={}, parallelism={}",
				 firstSolveParameters.threadsGlobal, firstSolveParameters.threadsWorker, parallelism);

		if (firstSolveParameters.deriveThreadsUsingSparkConfig) {

			final SparkConf sparkConf = sparkContext.getConf();
			final int driverCores = sparkConf.getInt("spark.driver.cores", 1);
			final int executorCores = sparkConf.getInt("spark.executor.cores", 1);

			// If only one setup, global solve will be run on driver so set threadsGlobal to driver core count.
			// Otherwise, global solve is run on executors so set threadsGlobal to executor core count.
			final int threadsGlobal = solveParameters.size() == 1 ? driverCores : executorCores;

			solveParameters.forEach(param -> {
				param.threadsGlobal = threadsGlobal;
				param.threadsWorker = executorCores;
			});

			try {
				final int sleepSeconds = 15;
				LOG.info("deriveParallelismValues: sleeping {} seconds to give workers a chance to connect", sleepSeconds);
				Thread.sleep(sleepSeconds * 1000L);
			} catch (final InterruptedException e) {
				LOG.warn("deriveParallelismValues: interrupted while sleeping", e);
			}

			// set parallelism to number of worker executors
			// see https://stackoverflow.com/questions/51342460/getexecutormemorystatus-size-not-outputting-correct-num-of-executors
			final int numberOfExecutorsIncludingDriver = sparkContext.sc().getExecutorMemoryStatus().size();
			final int numberOfWorkerExecutors = numberOfExecutorsIncludingDriver - 1;
			parallelism = Math.max(numberOfWorkerExecutors, 2);

			LOG.info("deriveParallelismValues: updated values, threadsGlobal={}, threadsWorker={}, parallelism={}",
					 firstSolveParameters.threadsGlobal, firstSolveParameters.threadsWorker, parallelism);
		}

		return parallelism;
	}

	protected static void cleanUpIntermediateStack(final RenderWebServiceParameters renderWeb, final String stack) {
		final RenderDataClient dataClient = renderWeb.getDataClient();
		try {
			dataClient.deleteStack(stack, null);
			LOG.info("cleanUpIntermediateStack: deleted stack {}", stack);
		} catch (final IOException e) {
			LOG.error("cleanUpIntermediateStack: error deleting stack {}", stack, e);
		}
	}

	private static final Logger LOG = LoggerFactory.getLogger(DistributedAlternatingDomainDecompositionSolver.class);
}

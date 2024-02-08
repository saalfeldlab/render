package org.janelia.render.client.spark.newsolver;

import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.render.client.newsolver.setup.DistributedSolveParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common shared utilities for distributed alignment or intensity correction runs on spark clusters.
 */
public class SparkDistributedSolveUtils {

	/**
	 * If requested in the solve parameters, derive the parallelism value to use for the run
	 * based upon the current spark context and a goal of each spark task using all cores on an executor.
     * Otherwise, use the default parallelism value from the spark context.
	 *
	 * @param  sparkContext         the current spark context.
	 * @param  solveParametersList  list of parameters for each solve in the run.
	 *
	 * @return spark parallelism value to use for the run.
	 */
	public static int deriveParallelismValues(final JavaSparkContext sparkContext,
											  final List<DistributedSolveParameters> solveParametersList) {

		// From https://spark.apache.org/docs/3.4.1/configuration.html#execution-behavior ...
		//   For these cluster managers, spark.default.parallelism is:
		//   - Local mode: number of cores on the local machine
		//   - Mesos fine grained mode: 8
		//   - Others: total number of cores on all executor nodes or 2, whichever is larger.
		int parallelism = sparkContext.defaultParallelism();
		final DistributedSolveParameters firstSolveParameters = solveParametersList.get(0);

		LOG.info("deriveParallelismValues: entry, threadsGlobal={}, threadsWorker={}, parallelism={}",
				 firstSolveParameters.threadsGlobal, firstSolveParameters.threadsWorker, parallelism);

		if (firstSolveParameters.deriveThreadsUsingSparkConfig) {

			final SparkConf sparkConf = sparkContext.getConf();
			final int driverCores = sparkConf.getInt("spark.driver.cores", 1);
			final int executorCores = sparkConf.getInt("spark.executor.cores", 1);

			// TODO: fix thread count padding and warnings if/when tile optimizer is improved
			final int threadsWorker;
			if (executorCores < 3) {
				LOG.warn("deriveParallelismValues: recommend at least 3 executor cores for optimization (you have {})",
						 executorCores);
				threadsWorker = executorCores;
			} else {
				threadsWorker = executorCores - 1;  // leave one core for the scheduler
			}

			final int threadsGlobal;
			if (solveParametersList.size() > 1) {
				// Multiple setups, so global solves will be run on executors.
				threadsGlobal = threadsWorker;
			} else {
				// Only one setup, so global solve will be run on driver.
				if (driverCores < 3) {
					LOG.warn("deriveParallelismValues: recommend at least 3 driver cores for optimization (you have {})",
							 driverCores);
					threadsGlobal = driverCores;
				} else {
				    threadsGlobal = driverCores - 1;  // leave one core for the scheduler
				}
			}

			solveParametersList.forEach(param -> {
				param.threadsGlobal = threadsGlobal;
				param.threadsWorker = threadsWorker;
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

	private static final Logger LOG = LoggerFactory.getLogger(SparkDistributedSolveUtils.class);
}

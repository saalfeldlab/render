package org.janelia.render.client.newsolver.setup;

import java.io.Serializable;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

/**
 * Parameters for distributed solver.
 *
 * @author Michael Innerberger
 */
@Parameters
public class DistributedSolveParameters implements Serializable {

	private static final long serialVersionUID = -4732166396721717685L;

	// global assembly solve parameters
	@Parameter(
			names = "--maxAllowedErrorGlobal",
			description = "Max allowed error global"
	)
	public Double maxAllowedErrorGlobal = 10.0;

	@Parameter(
			names = "--maxIterationsGlobal",
			description = "Max iterations global"
	)
	public Integer maxIterationsGlobal = 10000;

	@Parameter(
			names = "--maxPlateauWidthGlobal",
			description = "Max plateau width global"
	)
	public Integer maxPlateauWidthGlobal = 500;

	@Parameter(
			names = "--threadsWorker",
			description = "Number of threads to be used within each worker job (default:1)")
	public int threadsWorker = 1;

	@Parameter(
			names = "--threadsGlobal",
			description = "Number of threads to be used for global coarse solve (default: numProcessors/2)")
	public int threadsGlobal = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);

	@Parameter(
			names = "--deriveThreadsUsingSparkConfig",
			description = "If specified, override threadsWorker and threadsGlobal based upon Spark configuration",
			arity = 1)
	public boolean deriveThreadsUsingSparkConfig = false;

	public DistributedSolveParameters() {}

	public DistributedSolveParameters(
			final Double maxAllowedErrorGlobal,
			final Integer maxIterationsGlobal,
			final Integer maxPlateauWidthGlobal,
			final int threadsWorker,
			final int threadsGlobal,
			final boolean deriveThreadsUsingSparkConfig) {

		if (maxAllowedErrorGlobal < 0)
			throw new RuntimeException("MaxAllowedErrorGlobal has to be >= 0.");
		ensurePositive(maxIterationsGlobal, "MaxIterationsGlobal");
		ensurePositive(maxPlateauWidthGlobal, "MaxPlateauWidthGlobal");
		ensurePositive(threadsWorker, "ThreadsWorker");
		ensurePositive(threadsGlobal, "ThreadsGlobal");

		this.maxAllowedErrorGlobal = maxAllowedErrorGlobal;
		this.maxIterationsGlobal = maxIterationsGlobal;
		this.maxPlateauWidthGlobal = maxPlateauWidthGlobal;
		this.threadsWorker = threadsWorker;
		this.threadsGlobal = threadsGlobal;
		this.deriveThreadsUsingSparkConfig = deriveThreadsUsingSparkConfig;
	}

	protected static void ensurePositive(final Integer value, final String name) {
		if (value < 1)
			throw new RuntimeException(name + " has to be > 0.");
	}
}

package org.janelia.render.client.newsolver.setup;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import java.io.Serializable;

/**
 * Parameters for distributed solver.
 *
 * @author Michael Innerberger
 */
@Parameters
public class DistributedSolveParameters implements Serializable {

	// Initialization parameters
	@Parameter(
			names = "--blockSize",
			description = "The size of the blocks in z, which will be computed in paralell (default:500, min:3) "
	)
	public Integer blockSize = 500;

	@Parameter(
			names = "--minBlockSize",
			description = "The minimal size of the blocks in z, which will be computed in parallel (default: 50) "
	)
	public Integer minBlockSize = 50;

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
			description = "Number of threads to be used for global intensity correction (default: numProcessors/2)")
	public int threadsGlobal = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);

	public DistributedSolveParameters() {}

	public DistributedSolveParameters(
			final Integer blockSize,
			final Integer minBlockSize,
			final Double maxAllowedErrorGlobal,
			final Integer maxIterationsGlobal,
			final Integer maxPlateauWidthGlobal,
			final int threadsWorker,
			final int threadsGlobal) {

		if (blockSize < 3)
			throw new RuntimeException("Blocksize has to be >= 3.");
		if (minBlockSize < 1)
			throw new RuntimeException("MinBlockSize has to be > 0.");
		if (maxAllowedErrorGlobal < 0)
			throw new RuntimeException("MaxAllowedErrorGlobal has to be >= 0.");
		if (maxIterationsGlobal < 1)
			throw new RuntimeException("MaxIterationsGlobal has to be > 0.");
		if (maxPlateauWidthGlobal < 1)
			throw new RuntimeException("MaxPlateauWidthGlobal has to be > 0.");
		if (threadsWorker < 1)
			throw new RuntimeException("ThreadsWorker has to be > 0.");
		if (threadsGlobal < 1)
			throw new RuntimeException("ThreadsGlobal has to be > 0.");

		this.blockSize = blockSize;
		this.minBlockSize = minBlockSize;
		this.maxAllowedErrorGlobal = maxAllowedErrorGlobal;
		this.maxIterationsGlobal = maxIterationsGlobal;
		this.maxPlateauWidthGlobal = maxPlateauWidthGlobal;
		this.threadsWorker = threadsWorker;
		this.threadsGlobal = threadsGlobal;
	}
}

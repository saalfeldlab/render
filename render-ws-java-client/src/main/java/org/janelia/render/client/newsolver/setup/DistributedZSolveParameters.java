package org.janelia.render.client.newsolver.setup;

import java.io.Serializable;

import com.beust.jcommander.Parameter;

public class DistributedZSolveParameters extends DistributedSolveParameters implements Serializable {

	private static final long serialVersionUID = 4965504618911977763L;

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

	public DistributedZSolveParameters() {}

	public DistributedZSolveParameters(
			final Integer blockSize,
			final Integer minBlockSize,
			final Double maxAllowedErrorGlobal,
			final Integer maxIterationsGlobal,
			final Integer maxPlateauWidthGlobal,
			final int threadsWorker,
			final int threadsGlobal) {

		super( maxAllowedErrorGlobal, maxIterationsGlobal, maxPlateauWidthGlobal, threadsWorker, threadsGlobal );

		if (blockSize < 3)
			throw new RuntimeException("Blocksize has to be >= 3.");
		if (minBlockSize < 1)
			throw new RuntimeException("MinBlockSize has to be > 0.");

		this.blockSize = blockSize;
		this.minBlockSize = minBlockSize;
	}
}

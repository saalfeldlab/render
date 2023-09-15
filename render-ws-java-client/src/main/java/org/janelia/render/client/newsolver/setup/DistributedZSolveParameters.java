package org.janelia.render.client.newsolver.setup;

import java.io.Serializable;

import com.beust.jcommander.Parameter;

public class DistributedZSolveParameters extends DistributedSolveParameters implements Serializable {

	private static final long serialVersionUID = 4965504618911977763L;

	// Initialization parameters
	@Parameter(
			names = "--blockSize",
			description = "The z-size of the blocks which will be computed in parallel (default:500, min:1) "
	)
	public Integer blockSize = 500;

	public DistributedZSolveParameters() {}

	public DistributedZSolveParameters(
			final Integer blockSize,
			final Double maxAllowedErrorGlobal,
			final Integer maxIterationsGlobal,
			final Integer maxPlateauWidthGlobal,
			final int threadsWorker,
			final int threadsGlobal) {

		super( maxAllowedErrorGlobal, maxIterationsGlobal, maxPlateauWidthGlobal, threadsWorker, threadsGlobal );

		ensurePositive(blockSize, "BlockSize");

		this.blockSize = blockSize;
	}
}

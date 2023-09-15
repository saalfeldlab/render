package org.janelia.render.client.newsolver.setup;

import java.io.Serializable;

import com.beust.jcommander.Parameter;

public class DistributedXYSolveParameters extends DistributedSolveParameters implements Serializable {


	private static final long serialVersionUID = 2540783600361458647L;

	// Initialization parameters
	@Parameter(
			names = "--blockSizeX",
			description = "The x-size of the blocks which will be computed in parallel (default:25000, min:1) "
	)
	public Integer blockSizeX = 25000;

	@Parameter(
			names = "--minBlockSizeX",
			description = "The minimal x-size of the blocks which will be computed in parallel (default: 1000) "
	)
	public Integer minBlockSizeX = 1000;

	@Parameter(
			names = "--blockSizeY",
			description = "The y-size of the blocks which will be computed in parallel (default:25000, min:1) "
	)
	public Integer blockSizeY = 25000;

	@Parameter(
			names = "--minBlockSizeY",
			description = "The minimal y-size of the blocks which will be computed in parallel (default: 1000) "
	)
	public Integer minBlockSizeY = 1000;

	public DistributedXYSolveParameters() {}

	public DistributedXYSolveParameters(
			final Integer blockSizeX,
			final Integer minBlockSizeX,
			final Integer blockSizeY,
			final Integer minBlockSizeY,
			final Double maxAllowedErrorGlobal,
			final Integer maxIterationsGlobal,
			final Integer maxPlateauWidthGlobal,
			final int threadsWorker,
			final int threadsGlobal) {

		super( maxAllowedErrorGlobal, maxIterationsGlobal, maxPlateauWidthGlobal, threadsWorker, threadsGlobal );

		ensurePositive(blockSizeX, "BlockSizeX");
		ensurePositive(minBlockSizeX, "MinBlockSizeX");
		ensurePositive(blockSizeY, "BlockSizeY");
		ensurePositive(minBlockSizeY, "MinBlockSizeY");

		this.blockSizeX = blockSizeX;
		this.minBlockSizeX = minBlockSizeX;
		this.blockSizeY = blockSizeY;
		this.minBlockSizeY = minBlockSizeY;
	}
}

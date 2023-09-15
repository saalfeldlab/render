package org.janelia.render.client.newsolver.setup;

import java.io.Serializable;

import com.beust.jcommander.Parameter;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;

public class BlockPartitionParameters implements Serializable {


	private static final long serialVersionUID = -4012434713607797047L;

	// Initialization parameters
	@Parameter(
			names = "--blockSizeX",
			description = "The x-size of the blocks which will be computed in parallel (e.g.:25000, min:1) ")
	public Integer sizeX = Integer.MAX_VALUE;

	@Parameter(
			names = "--blockSizeY",
			description = "The y-size of the blocks which will be computed in parallel (e.g.:25000, min:1) ")
	public Integer sizeY = Integer.MAX_VALUE;
	
	@Parameter(
			names = "--blockSizeZ",
			description = "The z-size of the blocks which will be computed in parallel (e.g.:500, min:1) ")
	public Integer sizeZ = Integer.MAX_VALUE;

	public BlockPartitionParameters() {}

	public BlockPartitionParameters(
			final Integer blockSizeX,
			final Integer blockSizeY,
			final Integer blockSizeZ)
	{
		this.sizeX = (blockSizeX == null) ? Integer.MAX_VALUE : blockSizeX;
		this.sizeY = (blockSizeY == null) ? Integer.MAX_VALUE : blockSizeY;
		this.sizeZ = (blockSizeZ == null) ? Integer.MAX_VALUE : blockSizeZ;

		ensurePositive(this.sizeX, "BlockSizeX");
		ensurePositive(this.sizeY, "BlockSizeY");
		ensurePositive(this.sizeZ, "BlockSizeZ");
	}

	protected static void ensurePositive(final Integer value, final String name) {
		if (value < 1)
			throw new RuntimeException(name + " has to be > 0.");
	}
}

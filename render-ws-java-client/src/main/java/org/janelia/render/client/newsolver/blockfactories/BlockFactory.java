package org.janelia.render.client.newsolver.blockfactories;

import java.io.Serializable;
import java.util.List;
import java.util.stream.Collectors;

import org.janelia.alignment.spec.Bounds;
import org.janelia.render.client.newsolver.BlockCollection;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.assembly.WeightFunction;
import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;
import org.janelia.render.client.newsolver.setup.BlockPartitionParameters;

/**
 * Abstract class representing a factory for creating blocks.
 *
 * @author Michael Innerberger
 */
public abstract class BlockFactory implements Serializable {

	/**
	 * Define a collection of blocks.
	 *
	 * @param blockSolveParameterProvider Provider for block solve parameters.
	 * @param shiftBlocks Flag indicating whether blocks should be shifted.
	 * @return A collection of blocks.
	 */
	public abstract <M, R, P extends BlockDataSolveParameters<M, R, P>> BlockCollection<M, R, P> defineBlockCollection(
			final ParameterProvider<M, R, P> blockSolveParameterProvider, final boolean shiftBlocks);

	/**
	 * Create a weight function for a block.
	 *
	 * @param block The block for which the weight function is to be created.
	 * @return The weight function for the block.
	 */
	public abstract WeightFunction createWeightFunction(final BlockData<?, ?> block);

	/**
	 * Get the strategy for merging blocks.
	 *
	 * @return The {@link MergingStrategy}.
	 */
	public abstract MergingStrategy getMergingStrategy();

	protected <M, R, P extends BlockDataSolveParameters<M, R, P>> BlockCollection<M, R, P> blockCollectionFromLayout(
			final List<Bounds> blockLayout,
			final ParameterProvider<M, R, P> parameterProvider) {

		final List<BlockData<R, P>> blockDataList =
				blockLayout.stream()
						.map(originalBounds -> new BlockData<>(parameterProvider.create(),
															   originalBounds,
															   getBlockTileFilter()))
						.collect(Collectors.toList());
		return new BlockCollection<>(blockDataList);
	}

	protected abstract BlockTileBoundsFilter getBlockTileFilter();

	/**
	 * Create a block factory from block sizes.
	 *
	 * @param range The bounds of the whole stack to be divided
	 * @param blockPartition The partition parameters for the blocks.
	 * @return A block factory.
	 */
	public static BlockFactory fromBlockSizes(
			final Bounds range,
			final BlockPartitionParameters blockPartition)
	{
		final int minZ = range.getMinZ().intValue();
		final int maxZ = range.getMaxZ().intValue();

		if (blockPartition.hasXY()) {
			final Double minX = range.getMinX();
			final Double maxX = range.getMaxX();
			final Double minY = range.getMinY();
			final Double maxY = range.getMaxY();

			if (blockPartition.hasZ())
				return new XYZBlockFactory(minX, maxX, minY, maxY, minZ, maxZ, blockPartition.sizeX, blockPartition.sizeY, blockPartition.sizeZ);
			else
				return new XYBlockFactory(minX, maxX, minY, maxY, minZ, maxZ, blockPartition.sizeX, blockPartition.sizeY);
		} else {
			if (blockPartition.hasZ())
				return new ZBlockFactory(minZ, maxZ, blockPartition.sizeZ);
			else
				throw new IllegalArgumentException("At least one of the block sizes in X/Y or Z has to be specified.");
		}
	}
}

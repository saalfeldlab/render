package org.janelia.render.client.newsolver.blockfactories;

import java.io.Serializable;
import java.util.List;
import java.util.stream.Collectors;

import org.janelia.alignment.spec.Bounds;
import org.janelia.render.client.newsolver.BlockCollection;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.assembly.WeightFunction;
import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;

import static org.janelia.render.client.newsolver.blockfactories.BlockLayoutCreator.In;

/**
 * Factory for creating blocks by dividing a stack in z.
 * The blocks created by this factory have a linear weight function in z
 * and can be merged using linear blending. Therefore, this factory should
 * not be used in alternating block solving, since already one step should
 * produce a good result.
 *
 * @author Stephan Preibisch
 */
public class ZBlockFactory extends BlockFactory implements Serializable {

	private static final long serialVersionUID = 4169473785487008894L;

	final int minZ, maxZ, blockSize;

	/**
	 * Creates a partition of a stack in z.
	 * 
	 * @param minZ - first z slice
	 * @param maxZ - last z slice
	 * @param blockSize - desired block size
	 */
	public ZBlockFactory(final int minZ, final int maxZ, final int blockSize)
	{
		this.minZ = minZ;
		this.maxZ = maxZ;
		this.blockSize = blockSize;
	}

	@Override
	public <M, R, P extends BlockDataSolveParameters<M,R,P>> BlockCollection<M, R, P> defineBlockCollection(
			final ParameterProvider<M, R, P> blockSolveParameterProvider,
			final boolean shiftBlocks)
	{
		final List<Bounds> blockLayout;
		if (shiftBlocks)
			blockLayout = new BlockLayoutCreator().shiftedGrid(In.Z, minZ, maxZ, blockSize).create();
		else
			blockLayout = new BlockLayoutCreator().regularGrid(In.Z, minZ, maxZ, blockSize).create();

		// grow blocks such that they overlap
		final List<Bounds> scaledLayout = blockLayout.stream().map(b -> b.scaled(1.0, 1.0, 2.0)).collect(Collectors.toList());
		return blockCollectionFromLayout(scaledLayout, blockSolveParameterProvider);
	}

	@Override
	protected BlockTileBoundsFilter getBlockTileFilter() {
		return BlockTileBoundsFilter.Z_INSIDE;
	}

	@Override
	public MergingStrategy getMergingStrategy() {
		return MergingStrategy.LINEAR_BLENDING;
	}

	@Override
	public WeightFunction createWeightFunction(final BlockData<?, ?> block) {
		return new ZDistanceWeightFunction(block, 0.01);
	}

	static class ZDistanceWeightFunction implements WeightFunction {

		private final double midpoint;
		private final double minZ;
		private final double maxZ;
		// regularization to make weights of minZ and maxZ > 0
		private final double eps;

		public ZDistanceWeightFunction(final BlockData<?, ?> block, final double eps) {
			final Bounds populatedBlockBounds = block.getPopulatedBounds();
			this.minZ = populatedBlockBounds.getMinZ();
			this.maxZ = populatedBlockBounds.getMaxZ();
			this.midpoint = (maxZ + minZ) / 2.0;
			this.eps = eps;
		}

		@Override
		public double compute(final double x, final double y, final double z) {
			final double distanceToBoundary = (z < midpoint) ? (z - minZ) : (maxZ - z);
			return Math.max(0, distanceToBoundary + eps);
		}
	}
}

package org.janelia.render.client.newsolver.blockfactories;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;

import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.newsolver.BlockCollection;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.assembly.WeightFunction;
import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;

import static org.janelia.render.client.newsolver.blockfactories.BlockLayoutCreator.In;

public class ZBlockFactory extends BlockFactory implements Serializable
{
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
			final ParameterProvider< M, R, P > blockSolveParameterProvider )
	{
		final List<Bounds> blockBounds = new BlockLayoutCreator()
				.regularGrid(In.Z, minZ, maxZ, blockSize)
				.plus()
				.shiftedGrid(In.Z, minZ, maxZ, blockSize)
				.create();

		return blockCollectionFromLayout(blockBounds, blockSolveParameterProvider);
	}

	@Override
	protected ResolvedTileSpecCollection fetchTileSpecs(
			final Bounds bound,
			final RenderDataClient dataClient,
			final BlockDataSolveParameters<?, ?, ?> basicParameters) throws IOException {

		return dataClient.getResolvedTilesForZRange(basicParameters.stack(), bound.getMinZ(), bound.getMaxZ());
	}

	@Override
	protected boolean shouldBeIncluded(final Bounds tileBounds, final Bounds blockBounds) {
		// whole layer is always included
		return true;
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
			this.minZ = block.minZ();
			this.maxZ = block.maxZ();
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

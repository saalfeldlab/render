package org.janelia.render.client.newsolver.blockfactories;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.newsolver.BlockCollection;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.assembly.WeightFunction;
import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.janelia.render.client.newsolver.blockfactories.BlockLayoutCreator.In;

public class ZBlockFactory implements BlockFactory< ZBlockFactory >, Serializable
{
	private static final long serialVersionUID = 4169473785487008894L;

	final int minZ, maxZ, blockSize, minBlockSize;

	/**
	 * Implementation that balances blocksize using the averageblock size.
	 * 
	 * @param minZ - first z slice
	 * @param maxZ - last z slice
	 * @param blockSize - desired block size
	 * @param minBlockSize - minimal block size (can fail if this is too high to be accommodated)
	 */
	public ZBlockFactory( final int minZ, final int maxZ, final int blockSize, final int minBlockSize )
	{
		this.minZ = minZ;
		this.maxZ = maxZ;
		this.blockSize = blockSize;
		this.minBlockSize = minBlockSize;
	}

	@Override
	public <M, R, P extends BlockDataSolveParameters<M,R,P>> BlockCollection<M, R, P, ZBlockFactory> defineBlockCollection(
			final ParameterProvider< M, R, P > blockSolveParameterProvider )
	{
		final List<Bounds> blockBounds = new BlockLayoutCreator(minBlockSize)
				.regularGrid(In.Z, minZ, maxZ, blockSize)
				.plus()
				.shiftedGrid(In.Z, minZ, maxZ, blockSize)
				.create();

		// fetch metadata from render
		final BlockDataSolveParameters<?,?,?> basicParameters = blockSolveParameterProvider.basicParameters();
		final RenderDataClient dataClient = new RenderDataClient(
				basicParameters.baseDataUrl(),
				basicParameters.owner(),
				basicParameters.project());

		final ArrayList< BlockData< M, R, P, ZBlockFactory > > blockDataList = new ArrayList<>();

		// for each block, we know the z-range
		int id = 0;
		for (final Bounds bound : blockBounds ) {
			LOG.info("Try to load block " + id + ": " + bound);
			ResolvedTileSpecCollection rtsc = null;

			try {
				// TODO: trautmane
				// we fetch all TileSpecs for our z-range
				rtsc = dataClient.getResolvedTilesForZRange(basicParameters.stack(), bound.getMinZ(), bound.getMaxZ());
			} catch (final Exception e) {
				throw new RuntimeException("Failed to fetch data from render.", e);
			}

			LOG.info("Loaded " + rtsc.getTileIds().size() + " tiles.");
			final BlockData<M, R, P, ZBlockFactory> block = new BlockData<>(this, blockSolveParameterProvider.create(rtsc), id, rtsc);
			blockDataList.add(block);
			id++;
		}

		return new BlockCollection<>( blockDataList );
	}


	@Override
	public WeightFunction createWeightFunction(final BlockData<?, ?, ?, ZBlockFactory> block) {
		return new ZDistanceWeightFunction(block, 0.01);
	}

	private static class ZDistanceWeightFunction implements WeightFunction {

		private final double midpoint;
		private final double minZ;
		private final double maxZ;
		// regularization to make weights of minZ and maxZ > 0
		private final double eps;

		public ZDistanceWeightFunction(final BlockData<?, ?, ?, ZBlockFactory> block, final double eps) {
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

	private static final Logger LOG = LoggerFactory.getLogger(ZBlockFactory.class);
}

package org.janelia.render.client.newsolver.blockfactories;

import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.newsolver.BlockCollection;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.assembly.WeightFunction;
import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;

import static org.janelia.render.client.newsolver.blockfactories.BlockLayoutCreator.In;

public class XYZBlockFactory extends BlockFactory implements Serializable {

	private static final long serialVersionUID = 4436386605961332810L;

	final int minX, maxX, minY, maxY;
	final int minZ, maxZ;
	final int minBlockSizeX, minBlockSizeY, minBlockSizeZ;
	final int blockSizeX, blockSizeY, blockSizeZ;

	public XYZBlockFactory(
			final double minX, final double maxX,
			final double minY, final double maxY,
			final int minZ, final int maxZ,
			final int blockSizeX,
			final int blockSizeY,
			final int blockSizeZ,
			final int minBlockSizeX,
			final int minBlockSizeY,
			final int minBlockSizeZ
	) {
		this.minX = (int)Math.round(Math.floor(minX));
		this.maxX = (int)Math.round(Math.ceil(maxX));
		this.minY = (int)Math.round(Math.floor(minY));
		this.maxY = (int)Math.round(Math.ceil(maxY));
		this.minZ = minZ;
		this.maxZ = maxZ;
		this.blockSizeX = blockSizeX;
		this.blockSizeY = blockSizeY;
		this.blockSizeZ = blockSizeZ;
		this.minBlockSizeX = minBlockSizeX;
		this.minBlockSizeY = minBlockSizeY;
		this.minBlockSizeZ = minBlockSizeZ;
	}

	@Override
	public <M, R, P extends BlockDataSolveParameters<M, R, P>> BlockCollection<M, R, P> defineBlockCollection(
			final ParameterProvider<M, R, P> blockSolveParameterProvider)
	{
		final List<Bounds> blockLayout = new BlockLayoutCreator(new int[]{minBlockSizeX, minBlockSizeY, minBlockSizeZ})
				.regularGrid(In.X, minX, maxX, blockSizeX)
				.regularGrid(In.Y, minY, maxY, blockSizeY)
				.regularGrid(In.Z, minZ, maxZ, blockSizeZ)
				.plus()
				.shiftedGrid(In.X, minX, maxX, blockSizeX)
				.shiftedGrid(In.Y, minY, maxY, blockSizeY)
				.regularGrid(In.Z, minZ, maxZ, blockSizeZ)
				.plus()
				.regularGrid(In.X, minX, maxX, blockSizeX)
				.regularGrid(In.Y, minY, maxY, blockSizeY)
				.shiftedGrid(In.Z, minZ, maxZ, blockSizeZ)
				.create();

		return blockCollectionFromLayout(blockLayout, blockSolveParameterProvider);
	}

	@Override
	protected ResolvedTileSpecCollection fetchTileSpecs(
			final Bounds bound,
			final RenderDataClient dataClient,
			final BlockDataSolveParameters<?, ?, ?> basicParameters) throws IOException {

		return dataClient.getResolvedTiles(
				basicParameters.stack(),
				bound.getMinZ(), bound.getMaxZ(),
				null, // groupId,
				bound.getMinX(), bound.getMaxX(),
				bound.getMinY(), bound.getMaxY(),
				null); // matchPattern
	}

	@Override
	public WeightFunction createWeightFunction(final BlockData<?, ?, ?> block) {
		final WeightFunction xyWeightFunction = new XYBlockFactory.XYDistanceWeightFunction(block, 0.01);
		final WeightFunction zWeightFunction = new ZBlockFactory.ZDistanceWeightFunction(block, 0.01);
		return (x, y, z) -> xyWeightFunction.compute(x, y, z) * zWeightFunction.compute(x, y, z);
	}
}

package org.janelia.render.client.newsolver.blockfactories;

import org.janelia.alignment.spec.Bounds;
import org.janelia.render.client.newsolver.BlockCollection;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.assembly.WeightFunction;
import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;

import java.io.Serializable;
import java.util.List;
import java.util.stream.Collectors;

import static org.janelia.render.client.newsolver.blockfactories.BlockLayoutCreator.In;

public class XYZBlockFactory extends BlockFactory implements Serializable {

	private static final long serialVersionUID = 4436386605961332810L;

	final int minX, maxX, minY, maxY;
	final int minZ, maxZ;
	final int blockSizeX, blockSizeY, blockSizeZ;

	public XYZBlockFactory(
			final double minX, final double maxX,
			final double minY, final double maxY,
			final int minZ, final int maxZ,
			final int blockSizeX,
			final int blockSizeY,
			final int blockSizeZ
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
	}

	@Override
	public <M, R, P extends BlockDataSolveParameters<M, R, P>> BlockCollection<M, R, P> defineBlockCollection(
			final ParameterProvider<M, R, P> blockSolveParameterProvider,
			final boolean shiftBlocks)
	{
		final BlockLayoutCreator creator = new BlockLayoutCreator();
		if (shiftBlocks) {
			creator.shiftedGrid(In.X, minX, maxX, blockSizeX);
			creator.shiftedGrid(In.Y, minY, maxY, blockSizeY);
			creator.shiftedGrid(In.Z, minZ, maxZ, blockSizeZ);
		} else {
			creator.regularGrid(In.X, minX, maxX, blockSizeX);
			creator.regularGrid(In.Y, minY, maxY, blockSizeY);
			creator.regularGrid(In.Z, minZ, maxZ, blockSizeZ);
		}
		final List<Bounds> blockLayout = creator.create();

		// grow blocks such that they overlap
		final List<Bounds> scaledLayout = blockLayout.stream().map(b -> b.scaled(2.0, 2.0, 2.0)).collect(Collectors.toList());
		return blockCollectionFromLayout(scaledLayout, blockSolveParameterProvider);
	}

	@Override
	protected BlockTileBoundsFilter getBlockTileFilter() {
		return BlockTileBoundsFilter.XYZ_MIDPOINT;
	}

	@Override
	public WeightFunction createWeightFunction(final BlockData<?, ?> block) {
		final WeightFunction xyWeightFunction = new XYBlockFactory.XYDistanceWeightFunction(block, 0.01);
		final WeightFunction zWeightFunction = new ZBlockFactory.ZDistanceWeightFunction(block, 0.01);
		return (x, y, z) -> xyWeightFunction.compute(x, y, z) * zWeightFunction.compute(x, y, z);
	}
}

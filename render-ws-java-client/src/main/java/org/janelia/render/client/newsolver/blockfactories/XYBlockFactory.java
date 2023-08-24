package org.janelia.render.client.newsolver.blockfactories;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.function.Function;

import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.newsolver.BlockCollection;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;

public class XYBlockFactory implements BlockFactory< ZBlockFactory >, Serializable
{
	private static final long serialVersionUID = -2022190797935740332L;

	final int minX, maxX, minY, maxY, minZ, maxZ;
	final int minBlockSizeX, minBlockSizeY;
	final int blockSizeX, blockSizeY;

	public XYBlockFactory(
			final int minX, final int maxX,
			final int minY, final int maxY,
			final int minZ, final int maxZ,
			final int blockSizeX,
			final int blockSizeY,
			final int minBlockSizeX,
			final int minBlockSizeY )
	{
		this.minX = minX;
		this.maxX = maxX;
		this.minY = minY;
		this.maxY = maxY;
		this.minZ = minZ;
		this.maxZ = maxZ;
		this.blockSizeX = blockSizeX;
		this.blockSizeY = blockSizeY;
		this.minBlockSizeX = minBlockSizeX;
		this.minBlockSizeY = minBlockSizeY;
	}

	@Override
	public <M, R, P extends BlockDataSolveParameters<M, R, P>> BlockCollection<M, R, P, ZBlockFactory> defineBlockCollection(
			final ParameterProvider<M, R, P> blockSolveParameterProvider )
	{
		final BlockDataSolveParameters< ?,?,? > basicParameters = blockSolveParameterProvider.basicParameters();

		//
		// fetch metadata from render
		//
		final RenderDataClient r = new RenderDataClient(
				basicParameters.baseDataUrl(),
				basicParameters.owner(),
				basicParameters.project() );


		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ArrayList<Function<Double, Double>> createWeightFunctions( final BlockData<?, ?, ?, ZBlockFactory> block )
	{
		// TODO Auto-generated method stub
		return null;
	}

}

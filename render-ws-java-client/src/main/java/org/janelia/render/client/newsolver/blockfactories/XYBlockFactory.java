package org.janelia.render.client.newsolver.blockfactories;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.newsolver.BlockCollection;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blockfactories.ZBlockFactory.ZBlockInit;
import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;

public class XYBlockFactory implements BlockFactory< XYBlockFactory >, Serializable
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
	public <M, R, P extends BlockDataSolveParameters<M, R, P>> BlockCollection<M, R, P, XYBlockFactory> defineBlockCollection(
			final ParameterProvider<M, R, P> blockSolveParameterProvider )
	{
		// we use the same code as for Z ...
		// the LEFT sets are one more than the RIGHT sets and now simply form a grid of X*Y
		final List< ZBlockInit > initBlocksX = ZBlockFactory.defineBlockLayout( minX, maxX, blockSizeX, minBlockSizeX );
		final List< ZBlockInit > initBlocksY = ZBlockFactory.defineBlockLayout( minY, maxY, blockSizeY, minBlockSizeY );

		final BlockDataSolveParameters< ?,?,? > basicParameters = blockSolveParameterProvider.basicParameters();

		//
		// fetch metadata from render
		//
		final RenderDataClient r = new RenderDataClient(
				basicParameters.baseDataUrl(),
				basicParameters.owner(),
				basicParameters.project() );

		final ArrayList< BlockData< M, R, P, XYBlockFactory > > blockDataList = new ArrayList<>();

		// for each block, we know the z-range
		for ( int y = 0; y < initBlocksY.size(); ++y )
		{
			final ZBlockInit initBlockY = initBlocksY.get( y );

			System.out.println( "Y: "+ initBlockY.id + ": " + initBlockY.minZ() + " >> " + initBlockY.maxZ() + " [#"+(initBlockY.maxZ()-initBlockY.minZ()+1) + "]" );

			for ( int x = 0; x < initBlocksX.size(); ++x )
			{
				final ZBlockInit initBlockX = initBlocksY.get( x );

				// only RIGHT-RIGHT and LEFT-LEFT are combined
				if ( initBlockX.location() != initBlockY.location() )
					continue;

				System.out.println( "   X: "+ initBlockX.id + ": " + initBlockX.minZ() + " >> " + initBlockX.maxZ() + " [#"+(initBlockX.maxZ()-initBlockX.minZ()+1) + "]" );

				try
				{
					// TODO: trautmane
					// we fetch all TileSpecs for our x,y,z-range
					final ResolvedTileSpecCollection rtsc =
							r.getResolvedTiles(
									basicParameters.stack(),
									(double)minZ,
									(double)maxZ,
									null,//groupId,
									(double)initBlockX.minZ(),
									(double)initBlockX.maxZ(),
									(double)initBlockY.minZ(),
									(double)initBlockY.maxZ(),
									null );// matchPattern

					System.out.println( "Loaded " + rtsc.getTileIds().size() + " tiles.");

					final int id = y * initBlocksX.size() + x;

					final BlockData< M, R, P, XYBlockFactory > block = 
							new BlockData<>(
									this,
									blockSolveParameterProvider.create( rtsc ),
									id,
									rtsc );
	
					blockDataList.add( block );
				}
				catch (final Exception e)
				{
					System.out.println( "Failed to fetch data from render. stopping.");
					e.printStackTrace();
					return null;
				}
			}

		}

		return new BlockCollection<>( blockDataList );
	}

	@Override
	public ArrayList<Function<Double, Double>> createWeightFunctions( final BlockData<?, ?, ?, XYBlockFactory> block )
	{
		// TODO Auto-generated method stub
		return null;
	}

}

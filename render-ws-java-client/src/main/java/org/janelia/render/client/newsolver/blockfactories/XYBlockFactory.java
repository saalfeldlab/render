package org.janelia.render.client.newsolver.blockfactories;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.plugin.filter.EDM;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.newsolver.BlockCollection;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blockfactories.ZBlockFactory.ZBlockInit;
import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;

import net.imglib2.util.Pair;

public class XYBlockFactory implements BlockFactory< XYBlockFactory >, Serializable
{
	private static final long serialVersionUID = -2022190797935740332L;

	final int minX, maxX, minY, maxY;
	final int minZ, maxZ;
	final int minBlockSizeX, minBlockSizeY;
	final int blockSizeX, blockSizeY;

	public XYBlockFactory(
			final double minX, final double maxX,
			final double minY, final double maxY,
			final int minZ, final int maxZ,
			final int blockSizeX,
			final int blockSizeY,
			final int minBlockSizeX,
			final int minBlockSizeY )
	{
		this.minX = (int)Math.round(Math.floor( minX ));
		this.maxX = (int)Math.round(Math.ceil( maxX ));
		this.minY = (int)Math.round(Math.floor( minY ));
		this.maxY = (int)Math.round(Math.ceil( maxY ));
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

		System.out.println( "X: " + minX + " >>> " + maxX );

		for (final ZBlockInit bx : initBlocksX)
			System.out.println( bx.min + " >>> " + bx.max + ", " + bx.location + ", " + bx.id );

		System.out.println( "\nY: " + minY + " >>> " + maxY );

		for (final ZBlockInit by : initBlocksY)
			System.out.println( by.min + " >>> " + by.max + ", " + by.location + ", " + by.id );

		final BlockDataSolveParameters< ?,?,? > basicParameters = blockSolveParameterProvider.basicParameters();

		//
		// fetch metadata from render
		//
		final RenderDataClient r = new RenderDataClient(
				basicParameters.baseDataUrl(),
				basicParameters.owner(),
				basicParameters.project() );

		// 001_000003_078_20220405_180741.1241.0
		//  "z" : 1241.0,
		//  "minX" : 17849.0,
		//  "maxX" : 19853.0,
		//  "minY" : 10261.0,
		//  "maxY" : 12009.0,
		// should be found in [x: 17747 >>> 35096, LEFT, 1 ------ y: 400 >>> 17585, LEFT, 0]
		// ends up in Block id=1, id=9

		final ArrayList< BlockData< M, R, P, XYBlockFactory > > blockDataList = new ArrayList<>();

		for ( int y = 0; y < initBlocksY.size(); ++y )
		{
			final ZBlockInit initBlockY = initBlocksY.get( y );

			System.out.println( "Y: "+ initBlockY.id + ": " + initBlockY.minZ() + " >> " + initBlockY.maxZ() + " [#"+(initBlockY.maxZ()-initBlockY.minZ()+1) + "]" );

			for ( int x = 0; x < initBlocksX.size(); ++x )
			{
				final ZBlockInit initBlockX = initBlocksX.get( x );

				// only RIGHT-RIGHT and LEFT-LEFT are combined
				if ( initBlockX.location() != initBlockY.location() )
					continue;

				System.out.println( "   X: "+ initBlockX.id + ": " + initBlockX.minZ() + " >> " + initBlockX.maxZ() + " [#"+(initBlockX.maxZ()-initBlockX.minZ()+1) + "]" );

				ResolvedTileSpecCollection rtsc;

				try
				{
					// TODO: trautmane
					// we fetch all TileSpecs for our x,y,z-range
					
					rtsc = r.getResolvedTiles(
							basicParameters.stack(),
							(double)minZ,
							(double)maxZ,
							null,//groupId,
							(double)initBlockX.minZ(),
							(double)initBlockX.maxZ(),
							(double)initBlockY.minZ(),
							(double)initBlockY.maxZ(),
							null );// matchPattern
				}
				catch (final Exception e)
				{
					if ( e.getMessage().contains( "no tile specifications found" ) )
					{
						rtsc = null;
					}
					else
					{
						System.out.println( "Failed to fetch data from render. stopping.");
						e.printStackTrace();
						return null;
					}
				}

				if ( rtsc != null )
					System.out.println( "   Loaded " + rtsc.getTileIds().size() + " tiles.");
				else
					System.out.println( "   Loaded null tiles.");

				if ( rtsc == null || rtsc.getTileCount() == 0 )
				{
					System.out.println( "Since no tiles are in this XY block, continuing with next one.");
					continue;
				}

				final int id = y * initBlocksX.size() + x;

				System.out.println( "   XY: " + id + ": [" + initBlockX.minZ() + ", " + initBlockY.minZ() + ", " + minZ + "] >>> [" + initBlockX.maxZ() + ", " + initBlockY.maxZ() + ", " + maxZ + "]" );

				final BlockData< M, R, P, XYBlockFactory > block = 
						new BlockData<>(
								this,
								blockSolveParameterProvider.create( rtsc ),
								id,
								rtsc );

				blockDataList.add( block );
			}

		}

		return new BlockCollection<>( blockDataList );
	}

	@Override
	public ArrayList<Function<Double, Double>> createWeightFunctions( final BlockData<?, ?, ?, XYBlockFactory> block )
	{
		// the block must be able to compute it's center of mass and bounding box and return it
		// (which makes sense when we adjust intensities, because then the NEW model is for intensities, not for transformations)
		// (so actually the parameter object is able to compute the center of mass and BB of a block)
		// i.e. blockdata delegates the center-of-mass and BB computation to the parameter object

		// TODO: this is a very simple implementation that only working correctly for circular regions

		// compute current center-of-mass and bounding box
		final double[] center = block.centerOfMass();
		final Pair<double[], double[]> bb = block.boundingBox();
		final double[] maxDistance = maxDistance( center, bb );

		// we also define our own distance functions
		// here, z doesn't matter, only xy
		final ArrayList< Function< Double, Double > > weightF = new ArrayList<>();

		weightF.add( (x) -> 1.0 - Math.min( 1.0, Math.abs( center[ 0 ] - x ) / maxDistance[ 0 ] ) );
		weightF.add( (y) -> 1.0 - Math.min( 1.0, Math.abs( center[ 1 ] - y ) / maxDistance[ 1 ] ) );

		weightF.add( (z) -> 0.0 );

		// TODO: finish alternative implementation; weight function must have access to x and y simultaneously!
		// final Map<Integer, FloatProcessor> layerDistanceMaps = new HashMap<>(block.zToTileId().size());
		// block.zToTileId().forEach((z, layerIds) -> layerDistanceMaps.put(z, createLayerDistanceMap(block, layerIds)));

		return weightF;
	}

	public static double[] maxDistance( final double[] center, final Pair<double[], double[]> bb )
	{
		final double[] md = new double[ center.length ];

		for ( int d = 0; d < center.length; ++d )
			md[ d ] = Math.max( bb.getB()[ d ] - center[ d ], center[ d ] - bb.getA()[ d ]);

		return md;
	}

	public static FloatProcessor createLayerDistanceMap(final BlockData<?, ?, ?, ?> block, final Set<String> layerIds) {

		// sort the tile specs by tile id and convert them into simple masks
		final List<TileSpec> layerTiles = block.rtsc().getTileSpecs().stream()
				.filter(tileSpec -> layerIds.contains(tileSpec.getTileId()))
				.sorted(Comparator.comparing(TileSpec::getTileId))
				.collect(Collectors.toList());
		layerTiles.forEach(TileSpec::replaceFirstChannelImageWithItsMask);

		// the renderScale needs to be fairly small so that the entire MFOV area fits into one image
		final double renderScale = 0.01;
		final Pair<double[], double[]> bounds = block.boundingBox();
		final Bounds stackXYBounds = new Bounds(bounds.getA()[0], bounds.getA()[1], bounds.getB()[0], bounds.getB()[1]);

		final RenderParameters renderParameters = new RenderParameters();
		renderParameters.setBounds(stackXYBounds);
		renderParameters.setScale(renderScale);
		renderParameters.addTileSpecs(layerTiles);
		renderParameters.initializeDerivedValues();

		// render the layer into an 8-bit mask: 0=background (no tiles), everything else is foreground (tiles)
		final long pixelsToCache = 100_000_000L;
		final ImageProcessorCache ipCache = new ImageProcessorCache(pixelsToCache, false, false);
		final TransformMeshMappingWithMasks.ImageProcessorWithMasks ipwm =
				Renderer.renderImageProcessorWithMasks(renderParameters, ipCache);
		final ByteProcessor renderedLayerMask = ipwm.ip.convertToByteProcessor();

		// compute approximate Euclidean distance map to background (=0) where edges also count as background
		final FloatProcessor distanceMap = (new EDM()).makeFloatEDM(renderedLayerMask, 0, true);
		new ImagePlus("distance map", distanceMap).show();

		return distanceMap;
	}
}

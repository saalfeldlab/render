package org.janelia.render.client.solver.visualize;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Function;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.stack.MipmapPathBuilder;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.solver.MinimalTileSpec;
import org.janelia.render.client.solver.MultiResolutionSource;
import org.janelia.render.client.solver.visualize.lazy.Lazy;
import org.janelia.render.client.solver.visualize.lazy.RenderRA;
import org.janelia.render.client.solver.visualize.lazy.UpdatingRenderRA;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.util.volatiles.SharedQueue;
import bdv.util.volatiles.VolatileViews;
import mpicbg.models.AffineModel2D;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.type.volatiles.VolatileFloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;

public class RenderTools
{
	final static public String ownerFormat = "%s/owner/%s";
	final static public String stackListFormat = ownerFormat + "/stacks";
	final static public String stackFormat = ownerFormat + "/project/%s/stack/%s";
	final static public String stackBoundsFormat = stackFormat  + "/bounds";
	final static public String boundingBoxFormat = stackFormat + "/z/%d/box/%d,%d,%d,%d,%f";
	final static public String renderParametersFormat = boundingBoxFormat + "/render-parameters";

	final public static StackMetaData openStackMetaData(
			final String baseUrl,
			final String owner,
			final String project,
			final String stack ) throws IOException
	{
		final RenderDataClient renderDataClient = new RenderDataClient(baseUrl, owner, project );
		return renderDataClient.getStackMetaData( stack );
	}

	final static public int[] availableDownsamplings(
			final String baseUrl,
			final String owner,
			final String project,
			final String stack ) throws IOException
	{
		return availableDownsamplings( openStackMetaData( baseUrl, owner, project, stack ) );
	}

	final static public int[] availableDownsamplings(
			final StackMetaData sourceStackMetaData ) throws IOException
	{
		// Say you have scalings of 0.5, 0.25, 0.1
		// and I query 0.500000001
		// will it use 1.0 then - yes

		final MipmapPathBuilder mipmaps = sourceStackMetaData.getCurrentVersion().getMipmapPathBuilder();
		
		if ( mipmaps == null )
		{
			return new int[] { 1 }; // 
		}
		else
		{
			final int[] ds = new int[ mipmaps.getNumberOfLevels() ];

			for ( int i = 0; i < ds.length; ++i )
				ds[ i ] = (int)Math.round( Math.pow( 2, i ) );

			return ds;
		}
	}

	final static public Interval stackBounds( final StackMetaData sourceStackMetaData ) throws IOException
	{
		Bounds bounds = sourceStackMetaData.getStats().getStackBounds();
		
		return new FinalInterval(
				new long[] {
					Math.round( Math.floor( bounds.getMinX() ) ),
					Math.round( Math.floor( bounds.getMinY() ) ),
					Math.round( Math.floor( bounds.getMinZ() ) ) },
				new long[] {
					Math.round( Math.ceil( bounds.getMaxX() ) ),
					Math.round( Math.ceil( bounds.getMaxY() ) ),
					Math.round( Math.ceil( bounds.getMaxZ() ) ) } );
	}

	/**
	 * Fetch the raw image for an arbitrary scale (can crash if scale does not exist - if that is easier)
	 * 
	 * @param ipCache
	 * @param baseUrl
	 * @param owner
	 * @param project
	 * @param stack
	 * @param tileId
	 * @param scale - the preexisting downsampled image as stored on disk
	 * @return
	 */
	final static public BufferedImage renderImage(
			final ImageProcessorCache ipCache,
			final String baseUrl,
			final String owner,
			final String project,
			final String stack,
			final String tileId,
			final AffineTransform2D t,
			final double scale )
	{
		return null;
	}

	final static public ImageProcessorWithMasks renderImage(
			final ImageProcessorCache ipCache,
			final String baseUrl,
			final String owner,
			final String project,
			final String stack,
			final long x,
			final long y,
			final long z,
			final long w,
			final long h,
			final double scale,
			final boolean filter) {

		final String renderParametersUrlString = String.format(
				renderParametersFormat,
				baseUrl,
				owner,
				project,
				stack,
				z, // full res coordinates
				x, // full res coordinates
				y, // full res coordinates
				w, // full res coordinates
				h, // full res coordinates
				scale);

		// fetches the raw data 
		final RenderParameters renderParameters = RenderParameters.loadFromUrl(renderParametersUrlString); // we do have that info locally
		renderParameters.setDoFilter(filter);

		/*
		final BufferedImage image = renderParameters.openTargetImage(); // opens an empty buffer
		ArgbRenderer.render(renderParameters, image, ipCache); // loads the entire image and crops the requested size

		return image;
		*/

		return Renderer.renderImageProcessorWithMasks( renderParameters, ipCache );
	}

	public static BdvStackSource< ? > renderMultiRes(
			final ImageProcessorCache globalIpCache,
			final String baseUrl,
			final String owner,
			final String project,
			final String stack,
			final HashMap<String, AffineModel2D> idToModels,
			final HashMap<String, MinimalTileSpec> idToTileSpec,
			BdvStackSource< ? > source,
			final int numThreads ) throws IOException
	{
		final Interval interval = VisualizingRandomAccessibleInterval.computeInterval(
				idToModels,
				idToTileSpec,
				new double[] { 1.0, 1.0, 1.0 } );

		// TODO: determine optimal distribution of threads between render and fetch (using half and half for now)
		final int numFetchThreads = Math.max(numThreads / 2, 1);
		final int numRenderingThreads = Math.max(numThreads - numFetchThreads, 1);

		return renderMultiRes( globalIpCache, baseUrl, owner, project, stack, interval, source, numRenderingThreads, numFetchThreads );
	}

	public static BdvStackSource< ? > renderMultiRes(
			final ImageProcessorCache globalIpCache,
			final String baseUrl,
			final String owner,
			final String project,
			final String stack,
			final Interval fullResInterval,
			BdvStackSource< ? > source,
			final int numRenderingThreads,
			final int numFetchThreads ) throws IOException
	{
		return renderMultiRes( globalIpCache, baseUrl, owner, project, stack, fullResInterval, source, numRenderingThreads, numFetchThreads, null );
	}

	public static BdvStackSource< ? > renderMultiRes(
			final ImageProcessorCache globalIpCache,
			final String baseUrl,
			final String owner,
			final String project,
			final String stack,
			final Interval fullResInterval,
			BdvStackSource< ? > source,
			final int numRenderingThreads,
			final int numFetchThreads,
			final Function< Integer, AffineTransform2D > zToTransform ) throws IOException
	{
		// one common ImageProcessor cache for all
		final ImageProcessorCache ipCache;

		if ( globalIpCache == null )
		{
			final boolean recordStats = true;
			final boolean cacheOriginalsForDownSampledImages = true;
			ipCache = new ImageProcessorCache( Integer.MAX_VALUE, recordStats, cacheOriginalsForDownSampledImages );
		}
		else
		{
			ipCache = globalIpCache;
		}

		final ArrayList< Pair< RandomAccessibleInterval< VolatileFloatType >, AffineTransform3D > > multiRes = new ArrayList<>();

		final int[] ds = availableDownsamplings( baseUrl, owner, project, stack );

		// define queue here so that multiple FetcherThreads are used
		System.out.println("building SharedQueue with " + numFetchThreads + " FetcherThreads" );
		final SharedQueue queue = new SharedQueue(numFetchThreads, 1 );

		for ( final int downsampling : ds )
		{
			//LOG.info( "Assembling Multiresolution pyramid for downsampling=" + downsampling );

			final long[] min = new long[ fullResInterval.numDimensions() ];
			final long[] max = new long[ fullResInterval.numDimensions() ];

			for ( int d = 0; d < min.length; ++d )
			{
				min[ d ] = fullResInterval.min( d ) / downsampling;
				max[ d ] = fullResInterval.max( d ) / downsampling;
			}

			final Interval interval = new FinalInterval( min, max );

			System.out.println( "ds=" + downsampling + ", interval=" + interval );

			final RenderRA< FloatType > renderer = zToTransform != null ?
					new UpdatingRenderRA<>(
							baseUrl,
							owner,
							project,
							stack,
							fullResInterval.min( 2 ),
							fullResInterval.max( 2 ),
							ipCache,
							min,
							new FloatType(),
							1.0/downsampling,
							zToTransform ) :
					new RenderRA<>(baseUrl,
							owner,
							project,
							stack,
							fullResInterval.min( 2 ),
							fullResInterval.max( 2 ),
							ipCache,
							min,
							new FloatType(),
							1.0/downsampling );

			// blockSize should be power-of-2 and at least the minimal downsampling
			final int blockSizeXY = Math.max( 64, ds[ ds.length - 1 ] ); // does that make sense?
			final int[] blockSize = new int[] { blockSizeXY, blockSizeXY, 1 };

			// TODO: return it for invalidation
			CachedCellImg<FloatType, ?> cachedCellImg =
					Lazy.process(
						interval,
						blockSize,
						new FloatType(),
						AccessFlags.setOf( AccessFlags.VOLATILE ),
						renderer );

			final RandomAccessibleInterval<FloatType> cachedImg =
					Views.translate(
							cachedCellImg,
							min );

			final RandomAccessibleInterval< VolatileFloatType > volatileRA =
					VolatileViews.wrapAsVolatile( cachedImg, queue );

			// the virtual image is zeroMin, this transformation puts it into the global coordinate system
			final AffineTransform3D t = new AffineTransform3D();
			t.scale( downsampling );

			multiRes.add( new ValuePair<>( volatileRA, t )  );
		}

		if ( source == null )
		{
			BdvOptions options = Bdv.options().numSourceGroups( 1 ).frameTitle( project + "_" + stack ).numRenderingThreads( numRenderingThreads );
			final String windowName = owner + " " + project + " " + stack;
			source = BdvFunctions.show( new MultiResolutionSource( multiRes, windowName ), options );
		}
		else
		{
			source = BdvFunctions.show( new MultiResolutionSource( multiRes, project + "_" + stack ), Bdv.options().addTo( source ).numRenderingThreads( numRenderingThreads ) );
		}

		//source.setDisplayRange( 0, 4096 );

		return source;
	}

	public static void main( String[] args ) throws IOException
	{
		String baseUrl = "http://tem-services.int.janelia.org:8080/render-ws/v1";
		String owner = "Z0720_07m_VNC"; //"flyem";
		String project = "Sec32"; //"Z0419_25_Alpha3";
		String stack = "v1_acquire_trimmed"; //"v1_acquire_sp_nodyn_v2";

		StackMetaData meta = openStackMetaData(baseUrl, owner, project, stack);
		
		final int[] ds = availableDownsamplings( meta );
		Interval interval = stackBounds( meta );

		System.out.println( Util.printCoordinates( ds ) );
		System.out.println( Util.printInterval( interval ) );

		final boolean recordStats = true;

		// only cache original imageProcessors if you don't have mipmaps
		final boolean cacheOriginalsForDownSampledImages = false;
		// make imageProcessor cache large enough for masks and some images, but leave most RAM for BDV
		final long cachedPixels = 2000000;
		final ImageProcessorCache ipCache = new ImageProcessorCache( cachedPixels, recordStats, cacheOriginalsForDownSampledImages );

		// make most cores available for viewer
		final double totalThreadUsage = 0.8;
		final int numTotalThreads = (int) Math.floor(Runtime.getRuntime().availableProcessors() * totalThreadUsage);

		// TODO: determine optimal distribution of threads between render and fetch (using half and half for now)
		final int numFetchThreads = Math.max(numTotalThreads / 2, 1);
		final int numRenderingThreads = Math.max(numTotalThreads - numFetchThreads, 1);

		/*
		final boolean filter = false;

		int w = 12000;
		int h = 7500;
		int x = -6000;
		int y = -5000;
		int z = 3600;

		ImageProcessorWithMasks img1 = renderImage( ipCache, baseUrl, owner, project, stack, x, y, z, w, h, 1.0 / ds[ 4 ], filter );
		ImageProcessorWithMasks img2 = renderImage( ipCache, baseUrl, owner, project, stack, x, y, z, w, h, 1.0 / ds[ 3 ], filter );

		new ImageJ();
		final ImagePlus imp1 = new ImagePlus("img1 " + ds[ 4 ], img1.ip);
		final ImagePlus imp2 = new ImagePlus("img1 " + ds[ 3 ], img2.ip);
		imp1.show();
		imp2.show();
		*/

		BdvStackSource<?> img = RenderTools.renderMultiRes(
				ipCache, baseUrl, owner, project, stack, interval, null, numRenderingThreads, numFetchThreads );
		
		img.setDisplayRange( 0, 256 );
	}
}

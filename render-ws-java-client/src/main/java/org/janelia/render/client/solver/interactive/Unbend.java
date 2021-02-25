package org.janelia.render.client.solver.interactive;

import java.awt.Color;
import java.io.IOException;
import java.util.List;

import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.solver.visualize.RenderTools;
import org.janelia.render.client.solver.visualize.lazy.UpdatingRenderRA;

import bdv.util.BdvStackSource;
import ij.ImageJ;
import ij.ImagePlus;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Scale2D;
import net.imglib2.realtransform.Translation2D;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.Views;

public class Unbend
{
	private static void testAdditionalTransform(
			final String baseUrl,
			final String owner,
			final String project,
			final String stack )
	{
		// test applying transform
		final boolean filter = false;

		// always in world coordinates
		int w = 512;
		int h = 512;
		int x = 10000;
		int y = 1000;
		int z = 6151;

		double scale = 1.0 /8.0;
		ImageProcessorWithMasks img1 = RenderTools.renderImage( null, baseUrl, owner, project, stack, x, y, z, w, h, scale, filter );

		new ImageJ();
		final ImagePlus imp1 = new ImagePlus("img1", img1.ip);
		imp1.show();

		// world coordinates
		AffineTransform2D t = new AffineTransform2D();
		// simple translation test
		//t.translate( 256, 256 );

		// rotate by 45 degrees around the center of this patch
		t.translate( -(x + w/2.0), -(y + h/2.0) );
		t.rotate( Math.toRadians( 45 ) );
		t.translate( (x + w/2.0), (y + h/2.0) );

		// which part of the image do we need to fetch?
		final Interval blockInterval = Intervals.createMinMax( x, y, x + w - 1, y + h - 1 );
		final Interval transformedInterval =
				Intervals.smallestContainingInterval(
						UpdatingRenderRA.estimateBounds( t.inverse(), blockInterval ) );

		System.out.println( Util.printInterval( blockInterval ) );
		System.out.println( Util.printInterval( transformedInterval ) );

		final ImageProcessorWithMasks ipm = RenderTools.renderImage(
				null, baseUrl, owner, project, stack,
				transformedInterval.min( 0 ),
				transformedInterval.min( 1 ),
				z,
				transformedInterval.dimension( 0 ),
				transformedInterval.dimension( 1 ),
				scale,
				false );

		RandomAccessibleInterval fromRender;

		if ( ipm.ip.getBitDepth() == 8 )
		{
			fromRender = ArrayImgs.unsignedBytes( (byte[])ipm.ip.getPixels(), ipm.ip.getWidth(), ipm.ip.getHeight() );
		}
		else if ( ipm.ip.getBitDepth() == 16 )
		{
			fromRender = ArrayImgs.unsignedShorts( (short[])ipm.ip.getPixels(), ipm.ip.getWidth(), ipm.ip.getHeight() );
		}
		else if ( ipm.ip.getBitDepth() == 32 )
		{
			fromRender = ArrayImgs.floats( (float[])ipm.ip.getPixels(), ipm.ip.getWidth(), ipm.ip.getHeight() );
		}
		else
		{
			throw new RuntimeException( "imgtype " + ipm.ip.getBitDepth() + " not supported." );
		}

		ImageJFunctions.show( fromRender );

		// make the downsampled image sitting at 0,0 a full-scale version of itself
		AffineTransform2D correctImage = new AffineTransform2D();
		Translation2D offsetT = new Translation2D( transformedInterval.min( 0 ), transformedInterval.min( 1 ) );
		Scale2D scaleT = new Scale2D( scale, scale );

		correctImage = correctImage.preConcatenate( scaleT.inverse() );
		correctImage = correctImage.preConcatenate( offsetT );

		// before applying our transformation we make the downsampled block a full-scale image
		t = t.concatenate( correctImage );

		// now after applying the transform, we scale down again
		t = t.preConcatenate( scaleT );

		// TODO: do we need to scale t here since the result is a small block
		RandomAccessible transformed = RealViews.affine( Views.interpolate( Views.extendZero( fromRender ), new NLinearInterpolatorFactory() ), t );

		RandomAccessibleInterval finalImg = Views.interval( transformed, Intervals.createMinSize( Math.round( x*scale ), Math.round(y*scale), Math.round(w*scale), Math.round(h*scale)) );

		ImageJFunctions.show( finalImg );
		
	}

	public static void main( String[] args ) throws IOException
	{
		String baseUrl = "http://tem-services.int.janelia.org:8080/render-ws/v1";
		String owner = "Z0720_07m_VNC"; //"flyem";
		String project = "Sec32"; //"Z0419_25_Alpha3";
		String stack = "v1_acquire_trimmed_sp1"; //"v1_acquire_sp_nodyn_v2";
		String matchCollection = "Sec32_v1";

		testAdditionalTransform(baseUrl, owner, project, stack);
		SimpleMultiThreading.threadHaltUnClean();

		StackMetaData meta = RenderTools.openStackMetaData(baseUrl, owner, project, stack);
		Interval interval = RenderTools.stackBounds( meta );

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

		BdvStackSource<?> bdv = RenderTools.renderMultiRes(
				ipCache, baseUrl, owner, project, stack, interval, null, numRenderingThreads, numFetchThreads );
		bdv.setDisplayRange( 0, 256 );

		InteractiveSegmentedLine line = new InteractiveSegmentedLine( bdv );
		List< double[] > points = line.getResult();

		if ( points != null && points.size() > 0 )
			new VisualizeSegmentedLine( bdv, points, Color.yellow, Color.yellow.darker(), null ).install();


	}
}

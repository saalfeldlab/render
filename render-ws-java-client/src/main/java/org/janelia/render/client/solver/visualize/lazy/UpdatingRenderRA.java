package org.janelia.render.client.solver.visualize.lazy;

import java.io.IOException;
import java.util.function.Function;

import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.solver.visualize.RenderTools;

import ij.ImageJ;
import ij.ImagePlus;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;
import net.imglib2.Cursor;
import net.imglib2.FinalRealInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Scale2D;
import net.imglib2.realtransform.Translation2D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.Views;

public class UpdatingRenderRA<T extends RealType<T> & NativeType<T>> extends RenderRA<T>
{
	final Function< Integer, AffineTransform2D > zToTransform;

	public UpdatingRenderRA(
			final String baseUrl,
			final String owner,
			final String project,
			final String stack,
			final long minZ, // full-res stack
			final long maxZ, // full-res stack
			final ImageProcessorCache ipCache,
			final long[] min,
			final T type,
			final double scale,
			final Function< Integer, AffineTransform2D > zToTransform )
	{
		super(baseUrl, owner, project, stack, minZ, maxZ, ipCache, min, type, scale);

		this.zToTransform = zToTransform;
	}

	@Override
	protected void update( final RandomAccessibleInterval<T> output, final int x, final int y, final int z, final int w, final int h )
	{
		AffineTransform2D t = zToTransform.apply( z ).copy();

		if ( t.isIdentity() )
		{
			super.update(output, x, y, z, w, h);
		}
		else
		{
			// which part of the image do we need to fetch given the transformation
			final Interval blockInterval = Intervals.createMinMax( x, y, x + w - 1, y + h - 1 );
			final Interval transformedInterval =
					Intervals.smallestContainingInterval(
							UpdatingRenderRA.estimateBounds( t.inverse(), blockInterval ) );

			final ImageProcessorWithMasks ipm = RenderTools.renderImage(
					null, baseUrl, owner, project, stack,
					transformedInterval.min( 0 ),
					transformedInterval.min( 1 ),
					z,
					transformedInterval.dimension( 0 ),
					transformedInterval.dimension( 1 ),
					scale,
					false );

			if ( ipm == null ) // if the requested block contains no images, null will be returned
			{
				fillZero( output );
				return;
			}

			if ( ipm.ip.getBitDepth() == 8 )
			{
				transform(
						output,
						ArrayImgs.unsignedBytes( (byte[])ipm.ip.getPixels(), ipm.ip.getWidth(), ipm.ip.getHeight() ),
						transformedInterval,
						x,y,w,h,t );
						
			}
			else if ( ipm.ip.getBitDepth() == 16 )
			{
				transform(
						output,
						ArrayImgs.unsignedShorts( (short[])ipm.ip.getPixels(), ipm.ip.getWidth(), ipm.ip.getHeight() ),
						transformedInterval,
						x,y,w,h,t );
			}
			else if ( ipm.ip.getBitDepth() == 32 )
			{
				transform(
						output,
						ArrayImgs.floats( (float[])ipm.ip.getPixels(), ipm.ip.getWidth(), ipm.ip.getHeight() ),
						transformedInterval,
						x,y,w,h,t );
			}
			else
			{
				throw new RuntimeException( "imgtype " + ipm.ip.getBitDepth() + " not supported." );
			}
		}
	}

	protected < S extends RealType< S > > void transform(
			final RandomAccessibleInterval<T> output,
			final RandomAccessibleInterval< S > fromRender,
			final Interval transformedInterval,
			final int x, final int y, final int w, final int h,
			AffineTransform2D t )
	{
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

		RandomAccessible< S > transformed = RealViews.affine( Views.interpolate( Views.extendBorder( fromRender ), new NLinearInterpolatorFactory<>() ), t );
		RandomAccessibleInterval< S > input = Views.interval( transformed, Intervals.createMinSize( Math.round( x*scale ), Math.round(y*scale), Math.round(w*scale), Math.round(h*scale)) );

		final Cursor< S > in = Views.flatIterable( input ).cursor();
		final Cursor< T > out = Views.flatIterable( output ).cursor();

		while( out.hasNext() )
			out.next().setReal( in.next().getRealDouble() );
	}

	/**
	 * Calculate the boundary interval of an interval after it has been
	 * transformed.
	 *
	 * @param transform the 2d affine transform
	 * @param interval the original bounds
	 * @return the new bounds
	 */
	public static FinalRealInterval estimateBounds( final AffineTransform2D transform, final RealInterval interval )
	{
		assert interval.numDimensions() >= 2: "Interval dimensions do not match.";

		final double[] min = new double[ interval.numDimensions() ];
		final double[] max = new double[ min.length ];
		final double[] rMin = new double[ min.length ];
		final double[] rMax = new double[ min.length ];
		min[ 0 ] = interval.realMin( 0 );
		min[ 1 ] = interval.realMin( 1 );
		max[ 0 ] = interval.realMax( 0 );
		max[ 1 ] = interval.realMax( 1 );
		rMin[ 0 ] = rMin[ 1 ] = Double.MAX_VALUE;
		rMax[ 0 ] = rMax[ 1 ] = -Double.MAX_VALUE;
		for ( int d = 2; d < rMin.length; ++d )
		{
			rMin[ d ] = interval.realMin( d );
			rMax[ d ] = interval.realMax( d );
			min[ d ] = interval.realMin( d );
			max[ d ] = interval.realMax( d );
		}

		final double[] f = new double[ 3 ];
		final double[] g = new double[ 3 ];

		transform.apply( min, g );
		Util.min( rMin, g );
		Util.max( rMax, g );

		f[ 0 ] = max[ 0 ];
		f[ 1 ] = min[ 1 ];
		transform.apply( f, g );
		Util.min( rMin, g );
		Util.max( rMax, g );

		f[ 0 ] = min[ 0 ];
		f[ 1 ] = max[ 1 ];
		transform.apply( f, g );
		Util.min( rMin, g );
		Util.max( rMax, g );

		f[ 0 ] = max[ 0 ];
		f[ 1 ] = max[ 1 ];
		transform.apply( f, g );
		Util.min( rMin, g );
		Util.max( rMax, g );

		return new FinalRealInterval( rMin, rMax );
	}

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

		testAdditionalTransform(baseUrl, owner, project, stack);
	}
}

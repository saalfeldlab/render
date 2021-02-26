package org.janelia.render.client.solver.visualize.lazy;

import java.util.function.Function;

import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.solver.visualize.RenderTools;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;
import net.imglib2.Cursor;
import net.imglib2.FinalRealInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.img.array.ArrayImgs;
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

}

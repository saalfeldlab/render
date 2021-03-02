package org.janelia.render.client.solver.interactive;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.solver.visualize.RenderTools;
import org.janelia.render.client.solver.visualize.imglib2.VolatileTmp;
import org.janelia.render.client.solver.visualize.lazy.Lazy;
import org.janelia.render.client.solver.visualize.lazy.UpdatingRenderRA;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvStackSource;
import bdv.util.volatiles.SharedQueue;
import ij.ImageJ;
import ij.ImagePlus;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealPoint;
import net.imglib2.cache.Invalidate;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.cache.volatiles.VolatileCache;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Scale2D;
import net.imglib2.realtransform.Translation2D;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.type.volatiles.VolatileFloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.RealSum;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
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

	public static void simpleCacheTest()
	{
		Interval interval = new FinalInterval( 512, 512, 256 );

		final Random rnd = new Random( 35 );
		CachedCellImg<FloatType, ?> cachedCellImg =
				Lazy.process(
					interval,
					new int[] { 64, 64, 32 },
					new FloatType(),
					AccessFlags.setOf( AccessFlags.VOLATILE ),
					out -> Views.iterable( out ).forEach( p -> p.set( rnd.nextFloat() * 65535 )) );

		final RandomAccessibleInterval<FloatType> cachedImg =
				Views.translate(
						cachedCellImg,
						new long[] { 10, 10, 10 } );

		final SharedQueue queue = new SharedQueue( 8, 1 );
		final Pair< RandomAccessibleInterval< VolatileFloatType >, VolatileCache > pair = VolatileTmp.wrapAsVolatile( cachedImg, queue, null );

		Bdv source = BdvFunctions.show( pair.getA(), "gg" );

		while ( source != null )
		{
			SimpleMultiThreading.threadWait( 2000 );
			System.out.println( "repainting " );

			//cachedCellImg.getCache().invalidateAll();
			pair.getB().invalidateAll();
			source.getBdvHandle().getViewerPanel().requestRepaint();
		}
	}


	public static void main( String[] args ) throws IOException
	{
		//simpleCacheTest();
		//SimpleMultiThreading.threadHaltUnClean();

		String baseUrl = "http://tem-services.int.janelia.org:8080/render-ws/v1";
		String owner = "Z0720_07m_VNC"; //"flyem";
		String project = "Sec32"; //"Z0419_25_Alpha3";
		String stack = "v1_acquire_trimmed_sp1"; //"v1_acquire_sp_nodyn_v2";
		String matchCollection = "Sec32_v1";

		//testAdditionalTransform(baseUrl, owner, project, stack);
		//SimpleMultiThreading.threadHaltUnClean();

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

		// at first just identity transform, later update to use the 
		final Unbending unbending = new Unbending();
		final ArrayList< Invalidate<?> > caches = new ArrayList<>();

		List< double[] > points = new ArrayList<>();
		points.add( new double[] {18180.08737355983, 1215.890333894893, -157.65656608148038});
		points.add( new double[] {18180.08737355983, 1475.0200175143727, 749.2973265866995});
		points.add( new double[] {18189.853698805466, 1724.0546629692826, 1682.3963054607357});
		points.add( new double[] {18216.26065146877, 1791.5061812018662, 4593.851412228383});
		points.add( new double[] {18026.55565070638, 2470.9690334880047, 6412.092765652576});
		points.add( new double[] {18026.55565070639, 3128.7214768764475, 7769.737739455516});
		points.add( new double[] {18026.55565070639, 4020.1983406595014, 9125.525469792246});
		points.add( new double[] {18044.62319941457, 4538.78241451408, 10017.047437393852});
		points.add( new double[] {18069.563192165682, 4578.155671281922, 13029.065008203444});

		BdvStackSource<?> bdv = RenderTools.renderMultiRes(
				ipCache, baseUrl, owner, project, stack, interval, null, numRenderingThreads, numFetchThreads,
				unbending, caches );
		bdv.setDisplayRange( 0, 256 );

		InteractiveSegmentedLine line = new InteractiveSegmentedLine( bdv, points );
		points = line.getResult();

		if ( points != null && points.size() > 0 )
		{
			for ( final double[] p : points )
				System.out.println( Util.printCoordinates( p ) );

			new VisualizeSegmentedLine( bdv, points, Color.yellow, Color.yellow.darker(), null ).install();
		}

		ArrayList<Pair<Integer, double[]>> positions = positionPerZSlice(points, interval.min( 2 ), interval.max( 2 ), 0.01 );

		unbending.setTranslations( centerTranslations( positions ));
		
		// TODO: move points accordingly
		caches.forEach( c -> c.invalidateAll() );
		bdv.getBdvHandle().getViewerPanel().requestRepaint();
	}

	public static ArrayList<Pair<Integer, double[]>> centerTranslations( ArrayList<Pair<Integer, double[]>> positions )
	{
		RealSum x = new RealSum( positions.size() );
		RealSum y = new RealSum( positions.size() );

		positions.forEach( p -> {
			x.add( p.getB()[ 0 ]);
			y.add( p.getB()[ 1 ] );
			});

		final double avgX = x.getSum() / (double)positions.size();
		final double avgY = y.getSum() / (double)positions.size();

		positions.forEach( p -> {
			p.getB()[ 0 ] -= avgX;
			p.getB()[ 1 ] -= avgY;
			System.out.println( "z: " + p.getA() + ", x=" + p.getB()[ 0 ] + ", y=" + p.getB()[ 1 ] );
			});

		return positions;
	}

	public static ArrayList<Pair<Integer, double[]>> positionPerZSlice( final List< double[] > points, final long minZ, final long maxZ, final double maxLocErrZ )
	{
		// check that list has an increasing z
		double minPointZ = points.get( 0 )[ 2 ];
		double maxPointZ = points.get( 0 )[ 2 ];

		for ( int i = 1; i < points.size(); ++i )
		{
			if ( points.get( i )[ 2 ] < points.get( i - 1 )[ 2 ] )
			{
				throw new RuntimeException( "List does not monotonically increase in z, not supported." );
			}
			else
			{
				minPointZ = Math.min( minPointZ, points.get( i )[ 2 ] );
				maxPointZ = Math.max( maxPointZ, points.get( i )[ 2 ] );
			}
		}

		System.out.println( "points from " + minPointZ + " > " + maxPointZ );
		System.out.println( "slices from " + minZ + " > " + maxZ );

		if ( minZ < minPointZ || maxZ > maxPointZ )
			throw new RuntimeException( "Z range not entirely convered. Stoping." );

		final MonotoneCubicSpline spline =
				MonotoneCubicSpline.createMonotoneCubicSpline(
						points.stream().map( p -> new RealPoint( p ) ).collect( Collectors.toList() ) );

		final ArrayList<Pair<Integer, double[]>> positions = new ArrayList<>();
		final RealPoint p = new RealPoint( points.get( 0 ).length );
		double x = 0;

		for ( int z = (int)minZ; z <= maxZ; ++z )
		{
			x = descentToSlice(spline, x, z, p);
			positions.add( new ValuePair<>(z, new double[] { p.getDoublePosition( 0 ), p.getDoublePosition( 1 ) } ) );

			//System.out.println( "z: " + z + ", x=" + p.getDoublePosition( 0 ) + ", y=" + p.getDoublePosition( 1 ) );
		}

		return positions;
	}

	protected static double descentToSlice( final MonotoneCubicSpline spline, double x, final double targetZ, final RealPoint p )
	{
		double z, dx, gradientZ, dz;

		spline.interpolate( x, p );
		z = p.getDoublePosition( 2 );
		gradientZ = gradientAt(spline, x, p);
		dz = targetZ - z;
		//System.out.println( "x:"+ x + ", z:" + z + ", gradientZ:" + gradientZ + ", dz:" + dz );

		while ( Math.abs( dz ) > 1E-10 )
		{
			dx = (dz / gradientZ );
			x += dx;
			spline.interpolate( x, p );
			z = p.getDoublePosition( 2 );
			gradientZ = gradientAt(spline, x, p);
			dz = targetZ - z;
			//System.out.println( "x:"+ x + " (dx=" + dx + "), z:" + z + ", gradientZ:" + gradientZ + ", dz:" + dz );
		}

		return x;
	}

	protected static double gradientAt( final MonotoneCubicSpline spline, double x, final RealPoint p )
	{
		spline.interpolate( x, p );
		final double z0 = p.getDoublePosition( 2 );
		spline.interpolate( x + 1E-8, p );
		final double z1 = p.getDoublePosition( 2 );

		return (z1-z0)/1E-8;
	}
	
	public static class Unbending implements Function<Integer, AffineTransform2D>
	{
		AffineTransform2D identity = new AffineTransform2D();
		HashMap< Integer, AffineTransform2D > transforms = null;

		public void setTranslations( final ArrayList<Pair<Integer, double[]>> positions )
		{
			transforms = new HashMap<>();

			positions.forEach( p -> {
				final AffineTransform2D t = new AffineTransform2D();
				t.translate( p.getB() );
				transforms.put( p.getA(), t.inverse() );
			});
		}

		@Override
		public AffineTransform2D apply( final Integer z )
		{
			if ( transforms == null )
				return identity;
			else
				return transforms.get( z );
			/*
			AffineTransform2D t = new AffineTransform2D();
			// rotate by 45 degrees around the center of this patch
			t.translate( -10000, -1000 );
			t.rotate( Math.toRadians( 30 * ( z/(double)interval.dimension( 2 ) ) - 15  ) );
			t.translate( 10000, 1000 );
			return t;*/
		}
		
	}
}

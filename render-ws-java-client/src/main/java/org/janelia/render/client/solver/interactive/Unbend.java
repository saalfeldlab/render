package org.janelia.render.client.solver.interactive;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.ResolvedTileSpecCollection.TransformApplicationMethod;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackMetaData.StackState;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.solver.SolveTools;
import org.janelia.render.client.solver.visualize.RenderTools;

import bdv.util.BdvStackSource;
import net.imglib2.Interval;
import net.imglib2.RealPoint;
import net.imglib2.cache.Invalidate;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.util.Pair;
import net.imglib2.util.RealSum;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;

public class Unbend
{
	protected static void updatePoints( List< double[] > points, final double[] avg )
	{
		// spline goes through the points, applying the transformation is equal to setting it to the average
		for ( final double p[] : points )
		{
			p[ 0 ] = avg[ 0 ];
			p[ 1 ] = avg[ 1 ];
		}
	}

	protected static ArrayList<Pair<Integer, double[]>> centerTranslations( ArrayList<Pair<Integer, double[]>> positions, double[] avg )
	{
		RealSum x = new RealSum( positions.size() );
		RealSum y = new RealSum( positions.size() );

		positions.forEach( p -> {
			x.add( p.getB()[ 0 ]);
			y.add( p.getB()[ 1 ] );
			});

		final double avgX = x.getSum() / (double)positions.size();
		final double avgY = y.getSum() / (double)positions.size();

		if ( avg != null && avg.length >= 2 )
		{
			avg[ 0 ] = avgX;
			avg[ 1 ] = avgY;
		}

		positions.forEach( p -> {
			p.getB()[ 0 ] -= avgX;
			p.getB()[ 1 ] -= avgY;
			System.out.println( "z: " + p.getA() + ", x=" + p.getB()[ 0 ] + ", y=" + p.getB()[ 1 ] );
			});

		return positions;
	}

	protected static ArrayList<Pair<Integer, double[]>> positionPerZSlice( final List< double[] > points, final long minZ, final long maxZ )
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

	public static void main( String[] args ) throws IOException
	{
		String baseUrl = "http://tem-services.int.janelia.org:8080/render-ws/v1";
		String owner = "cellmap";
		String project = "jrc_ut23_0590_100"; //"Z0419_25_Alpha3";
		String stack = "v1_acquire_align"; //"v1_acquire_sp_nodyn_v2";
		String targetStack = stack + "_straightened";

		final RenderDataClient renderDataClient = new RenderDataClient(baseUrl, owner, project );
		final StackMetaData meta =  renderDataClient.getStackMetaData( stack );
		//final StackMetaData meta = RenderTools.openStackMetaData(baseUrl, owner, project, stack);
		final Interval interval = RenderTools.stackBounds( meta );

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
		final int numFetchThreads = Math.max(64, 1);
		final int numRenderingThreads = Math.max(numTotalThreads - numFetchThreads, 1);

		// at first just identity transform, later update to use the 
		final Unbending unbending = new Unbending();
		final ArrayList< Invalidate<?> > caches = new ArrayList<>();

		List< double[] > points = new ArrayList<>();

		/*
		points.add( new double[] {18180.08737355983, 1044.0922756053733, -62.756698404699364});
		points.add( new double[] {18180.08737355983, 1475.0200175143727, 749.2973265866995});
		points.add( new double[] {18189.853698805466, 1724.0546629692826, 1682.3963054607357});
		points.add( new double[] {18216.26065146877, 1791.5061812018662, 4593.851412228383});
		points.add( new double[] {18026.55565070638, 2470.9690334880047, 6412.092765652576});
		points.add( new double[] {18026.55565070639, 3128.7214768764475, 7769.737739455516});
		points.add( new double[] {18026.55565070639, 4020.1983406595014, 9125.525469792246});
		points.add( new double[] {18044.623199414567, 4496.445482984773, 10431.538583396674});
		points.add( new double[] {18069.563192165682, 4781.255819355375, 12703.129389095324});
		*/

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

		final ArrayList<Pair<Integer, double[]>> positions = positionPerZSlice(points, interval.min( 2 ), interval.max( 2 ) );
		final double[] avg = new double[ 2 ];

		unbending.setTranslations( centerTranslations( positions, avg ));

		caches.forEach( c -> c.invalidateAll() );
		updatePoints( points, avg );
		bdv.getBdvHandle().getViewerPanel().requestRepaint();

		// saving
		if ( targetStack != null )
		{
			System.out.println( "saving target stack " + targetStack );
	
			//final RenderDataClient renderDataClient = new RenderDataClient(baseUrl, owner, project );
			final RenderDataClient targetDataClient = new RenderDataClient(baseUrl, owner, project );
	
			targetDataClient.setupDerivedStack(meta, targetStack);
	
			for ( long z = interval.min( 2 ); z <= interval.max( 2 ); ++z )
			{
				final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles( stack, (double)z );
	
				for (final TileSpec tileSpec : resolvedTiles.getTileSpecs())
				{
					final String tileId = tileSpec.getTileId();
					final AffineTransform2D model = unbending.transforms.get( (int)z );
	
					if ( model != null )
					{
						resolvedTiles.addTransformSpecToTile( tileId,
								SolveTools.getTransformSpec( model ),
								TransformApplicationMethod.PRE_CONCATENATE_LAST );
					}
				}
	
				if ( resolvedTiles.getTileCount() > 0 )
					targetDataClient.saveResolvedTiles( resolvedTiles, targetStack, null );
				else
					System.out.println( "skipping tile spec save since no specs are left to save" );
			}
	
			System.out.println( "saveTargetStackTiles: exit" );
	
	
			targetDataClient.setStackState( targetStack, StackState.COMPLETE );
		}

	}

}

package org.janelia.render.client.solver.interactive;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Function;

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
import net.imglib2.cache.Invalidate;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.util.LinAlgHelpers;
import net.imglib2.util.Pair;
import net.imglib2.util.RealSum;
import net.imglib2.util.Util;

public class Untwist
{
	public static double[] rotationAxis = new double[] { 1, 0 };

	public static class Untwisting implements Function<Integer, AffineTransform2D>
	{
		AffineTransform2D identity = new AffineTransform2D();
		HashMap< Integer, AffineTransform2D > transforms = null;

		public void setRotation(
				final ArrayList<Pair<Integer, double[]>> positionA,
				final ArrayList<Pair<Integer, double[]>> positionB )
		{
			transforms = new HashMap<>();

			if ( positionA.size() != positionB.size() )
				throw new RuntimeException( "position lists do not have the same size." );

			RealSum x = new RealSum( positionA.size() );
			RealSum y = new RealSum( positionA.size() );

			final double[] xc = new double[ positionA.size() ];
			final double[] yc = new double[ positionA.size() ];
			//final double[] l = new double[ positionA.size() ];
			final double[] angle = new double[ positionA.size() ];

			final double[] vector = new double[ 2 ];

			for ( int i = 0; i < positionA.size(); ++i ) 
			{
				final Pair<Integer, double[]> pairA = positionA.get( i );
				final Pair<Integer, double[]> pairB = positionB.get( i );

				if ( pairA.getA().intValue() != pairB.getA().intValue() )
					throw new RuntimeException( "position lists do not have the same index at i=" + i );

				final double x0 = pairA.getB()[ 0 ];
				final double y0 = pairA.getB()[ 1 ];

				final double x1 = pairB.getB()[ 0 ];
				final double y1 = pairB.getB()[ 1 ];

				vector[ 0 ] = (x1 - x0);
				vector[ 1 ] = (y1 - y0);

				if ( pairA.getA().intValue() == 6470 )
					System.out.println( x0 + "," + y0 + " -> " + x1 + "," + y1 + " - v" + Util.printCoordinates( vector ) );
				// point of rotation and translation center
				xc[ i ] = vector[ 0 ]/2.0 + x0;
				yc[ i ] = vector[ 1 ]/2.0 + y0;

				x.add( xc[ i ] );
				y.add( yc[ i ] );

				if ( pairA.getA().intValue() == 6470 )
					System.out.println( "c: " + xc[ i ] + "," + yc[ i ] );
				
				//l[ i ] = Math.sqrt( vector[ 0 ]*vector[ 0 ] + vector[ 1 ]*vector[ 1 ] );

				// e.g. (1,1) -> (1,0) = 45 degrees
				LinAlgHelpers.normalize( vector );

				if ( pairA.getA().intValue() == 6470 )
				{
					System.out.println( "nv: " + Util.printCoordinates( vector ) );
					System.out.println( "dot: " + LinAlgHelpers.dot( vector, rotationAxis ) );
				}

				// TODO: just using the sign of the y vector to determine if the rotation is + or -
				// this will break if the rotation axis is not the x-axis or maybe on other things?
				angle[ i ] = Math.acos( LinAlgHelpers.dot( vector, rotationAxis ) ) * Math.signum( vector[ 1 ] );

				if ( pairA.getA().intValue() == 6470 )
					System.out.println( "angle: " + angle[ i ] + " " + Math.toDegrees( angle[ i ] ) );

				// TODO: not sure about these hacks that do not allow flipping the sample around
				if ( Math.toDegrees( angle[ i ] ) > 90 )
					angle[ i ] = Math.toRadians( Math.toDegrees( angle[ i ] ) - 180 );
				else if ( Math.toDegrees( angle[ i ] ) < -90 )
					angle[ i ] = Math.toRadians( Math.toDegrees( angle[ i ] ) + 180);

				if ( pairA.getA().intValue() == 6470 )
					System.out.println( "angle: " + angle[ i ] + " " + Math.toDegrees( angle[ i ] ) );
			}

			final double avgX = x.getSum() / (double)positionA.size();
			final double avgY = y.getSum() / (double)positionA.size();

			for ( int i = 0; i < positionA.size(); ++i ) 
			{
				final AffineTransform2D t = new AffineTransform2D();

				// translate center point to 0,0
				t.translate( -xc[ i ], -yc[ i ] );

				// rotate around center
				t.rotate( -angle[ i ] );

				// move to the avg position
				t.translate( avgX, avgY );

				transforms.put( positionA.get( i ).getA(), t );

				if ( positionA.get( i ).getA().intValue() == 6470 )
				{
					double[] p0 = positionA.get( i ).getB().clone();
					double[] p1 = positionB.get( i ).getB().clone();
					double[] pc = new double[] { xc[i], yc[i]};

					System.out.println( "p0: " + Util.printCoordinates( p0 ) );
					System.out.println( "p1: " + Util.printCoordinates( p1 ) );
					System.out.println( "pc: " + Util.printCoordinates( pc ) );

					t.apply( p0, p0 );
					t.apply( p1, p1 );
					t.apply( pc, pc );

					System.out.println( "t(p0): " + Util.printCoordinates( p0 ) );
					System.out.println( "t(p1): " + Util.printCoordinates( p1 ) );
					System.out.println( "t(pc): " + Util.printCoordinates( pc ) );

					//System.exit( 0 );
				}
			}
		}

		@Override
		public AffineTransform2D apply( final Integer z )
		{
			if ( transforms == null )
				return identity;
			else
				return transforms.get( z );
		}
	}

	protected static void updatePoints( List< double[] > points, HashMap< Integer, AffineTransform2D > transforms, final int minZ, final int maxZ )
	{
		// spline goes through the points
		for ( final double p[] : points )
		{
			int z = (int)Math.round( p[ 2 ] );

			z = Math.min(z, maxZ);
			z = Math.max(z, minZ);

			transforms.get( z ).apply( p, p );
		}
	}

	public static void main( String[] args ) throws IOException
	{
		String baseUrl = "http://tem-services.int.janelia.org:8080/render-ws/v1";
		String owner = "Z0720_07m_VNC";
		String project = "Sec26"; //"Z0419_25_Alpha3";
		String stack = "v8_acquire_trimmed_align"; //"v1_acquire_sp_nodyn_v2";
		String targetStack = "v8_acquire_trimmed_align_untwisted";

		/*
		String baseUrl = "http://tem-services.int.janelia.org:8080/render-ws/v1";
		String owner = "cosem"; //"flyem";
		String project = "aic_desmosome_2"; //"Z0419_25_Alpha3";
		String stack = "v1_acquire_align_adaptive_2"; //"v1_acquire_sp_nodyn_v2";
		String targetStack = "v1_acquire_align_adaptive_2_untwisted";
		*/

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
		final int numFetchThreads = Math.max(numTotalThreads / 2, 1);
		final int numRenderingThreads = Math.max(numTotalThreads - numFetchThreads, 1);

		// at first just identity transform, later update to use the 
		final Untwisting untwisting = new Untwisting();
		final ArrayList< Invalidate<?> > caches = new ArrayList<>();

		List< double[] > pointsA = new ArrayList<>();
		List< double[] > pointsB = new ArrayList<>();

		/*
		pointsA.add( new double[] {18180.08737355983, 1044.0922756053733, -62.756698404699364});
		pointsA.add( new double[] {18180.08737355983, 1475.0200175143727, 749.2973265866995});
		pointsA.add( new double[] {18189.853698805466, 1724.0546629692826, 1682.3963054607357});
		pointsA.add( new double[] {18216.26065146877, 1791.5061812018662, 4593.851412228383});
		pointsA.add( new double[] {18026.55565070638, 2470.9690334880047, 6412.092765652576});
		pointsA.add( new double[] {18026.55565070639, 3128.7214768764475, 7769.737739455516});
		pointsA.add( new double[] {18026.55565070639, 4020.1983406595014, 9125.525469792246});
		pointsA.add( new double[] {18044.623199414567, 4496.445482984773, 10431.538583396674});
		pointsA.add( new double[] {18069.563192165682, 4781.255819355375, 12703.129389095324});

		pointsB.add( new double[] {8180.08737355983, 1044.0922756053733-500, -62.756698404699364});
		pointsB.add( new double[] {8180.08737355983, 1475.0200175143727-500, 749.2973265866995});
		pointsB.add( new double[] {8189.853698805466, 1724.0546629692826-500, 1682.3963054607357});
		pointsB.add( new double[] {8216.26065146877, 1791.5061812018662-500, 4593.851412228383});
		pointsB.add( new double[] {8026.55565070638, 2470.9690334880047-500, 6412.092765652576});
		pointsB.add( new double[] {8026.55565070639, 3128.7214768764475-500, 7769.737739455516});
		pointsB.add( new double[] {8026.55565070639, 4020.1983406595014-500, 9125.525469792246});
		pointsB.add( new double[] {8044.623199414567, 4496.445482984773-500, 10431.538583396674});
		pointsB.add( new double[] {8069.563192165682, 4781.255819355375-500, 12703.129389095324});
		*/

		BdvStackSource<?> bdv = RenderTools.renderMultiRes(
				ipCache, baseUrl, owner, project, stack, interval, null, numRenderingThreads, numFetchThreads,
				untwisting, caches );
		bdv.setDisplayRange( 0, 256 );

		InteractiveSegmentedLine lineA = new InteractiveSegmentedLine( bdv, pointsA );
		pointsA = lineA.getResult();

		if ( pointsA != null && pointsA.size() > 0 )
		{
			for ( final double[] p : pointsA )
				System.out.println( Util.printCoordinates( p ) );

			new VisualizeSegmentedLine( bdv, pointsA, Color.yellow, Color.yellow.darker(), null ).install();
		}
		else
		{
			System.out.println( "No points defined. stopping.");
			return;
		}

		InteractiveSegmentedLine lineB = new InteractiveSegmentedLine( bdv, pointsB );
		pointsB = lineB.getResult();

		if ( pointsB != null && pointsB.size() > 0 )
		{
			for ( final double[] p : pointsB )
				System.out.println( Util.printCoordinates( p ) );

			new VisualizeSegmentedLine( bdv, pointsB, Color.yellow, Color.yellow.darker(), null ).install();
		}
		else
		{
			System.out.println( "No points defined. stopping.");
			return;
		}

		/*
		if ( pointsA != null && pointsB != null && pointsA.size() > 0 && pointsB.size() > 0 )
		{
			for ( final double[] p : pointsA )
				System.out.println( Util.printCoordinates( p ) );

			System.out.println();

			for ( final double[] p : pointsB )
				System.out.println( Util.printCoordinates( p ) );

			new VisualizeSegmentedLine( bdv, pointsA, Color.yellow, Color.yellow.darker(), null ).install();
			new VisualizeSegmentedLine( bdv, pointsB, Color.yellow, Color.yellow.darker(), null ).install();
		}*/

		System.out.println( "updating...");

		final ArrayList<Pair<Integer, double[]>> positionsA =
				Unbend.positionPerZSlice(pointsA, interval.min( 2 ), interval.max( 2 ) );

		final ArrayList<Pair<Integer, double[]>> positionsB =
				Unbend.positionPerZSlice(pointsB, interval.min( 2 ), interval.max( 2 ) );

		untwisting.setRotation( positionsB, positionsA );

		caches.forEach( c -> c.invalidateAll() );
		updatePoints( pointsA, untwisting.transforms, (int)interval.min( 2 ), (int)interval.max( 2 ) );
		updatePoints( pointsB, untwisting.transforms, (int)interval.min( 2 ), (int)interval.max( 2 ) );
		bdv.getBdvHandle().getViewerPanel().requestRepaint();

		// TODO: basically same code as in Unbend
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
					final AffineTransform2D model = untwisting.transforms.get( (int)z );
	
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

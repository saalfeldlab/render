package org.janelia.render.client.solver.interactive;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection.TransformApplicationMethod;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackMetaData.StackState;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.solver.RunParameters;
import org.janelia.render.client.solver.SolveTools;
import org.janelia.render.client.solver.visualize.RenderTools;

import bdv.util.BdvStackSource;
import mpicbg.models.AffineModel2D;
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
		// spline goes through the points
		for ( final double p[] : points )
		{
			p[ 0 ] -= ( p[ 0 ] - avg[ 0 ] );
			p[ 1 ] -= ( p[ 1 ] - avg[ 1 ] );
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
		String owner = "cosem"; //"flyem";
		String project = "aic_desmosome_2"; //"Z0419_25_Alpha3";
		String stack = "v1_acquire_align_adaptive_2_straight"; //"v1_acquire_sp_nodyn_v2";
		String targetStack = "v1_acquire_align_adaptive_2_straight2";
		//String matchCollection = "Sec32_v1";

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
		points.add( new double[] {-477.31683782446737, 592.4258203179772, -4.3918767654849376});
		points.add( new double[] {-477.31683782446737, 593.8818455484472, 131.01846966823223});
		points.add( new double[] {-477.31683782446737, 599.7059464703275, 250.4125385667786});
		points.add( new double[] {-477.31683782446737, 601.1619717007975, 347.9662290082737});
		points.add( new double[] {-477.316837824464, 602.6179969312676, 442.6078689888287});
		points.add( new double[] {-477.3168378244672, 573.497492321866, 605.6826948014773});
		points.add( new double[] {-477.31683782446584, 567.6733913999857, 749.829192618015});
		points.add( new double[] {-477.3168378244674, 567.6733913999856, 1001.7215574893384});
		points.add( new double[] {-477.31683782446026, 569.3714799107054, 1345.1333711736584});
		points.add( new double[] {-477.3168378244675, 579.3215932437461, 1472.623360084688});
		points.add( new double[] {-477.3168378244673, 588.2162270346626, 1711.3791966306708});
		points.add( new double[] {-477.3168378244676, 581.1056265013045, 1830.3632455555294});
		points.add( new double[] {-477.3168378244676, 592.1311155595812, 1974.0180404189136});
		points.add( new double[] {-477.3168378244678, 594.861410401352, 2086.8159001974755});
		points.add( new double[] {-477.31683782446777, 602.1783176262506, 2204.9043104707744});
		points.add( new double[] {-477.3168378244586, 603.4939269403629, 2258.3060885390646});
		points.add( new double[] {-477.31683782446765, 612.2845891755685, 2334.910430874428});
		points.add( new double[] {-477.31683782446675, 606.0055447218502, 2406.4915376468166});
		points.add( new double[] {-477.31683782446765, 606.0055447218502, 2491.8865422173853});
		points.add( new double[] {-477.31683782446765, 613.5403980663122, 2616.211622401008});
		points.add( new double[] {-477.31683782446765, 616.0520158477996, 2735.5134670216557});
		points.add( new double[] {-477.31683782446765, 623.5868691922615, 2862.3501649867653});
		points.add( new double[] {-477.31683782446765, 631.1217225367235, 2917.6057561794864});
		points.add( new double[] {-477.31683782446765, 622.3310603015178, 3000.489142968568});
		points.add( new double[] {-477.3168378244677, 637.2779554008915, 3124.019285015841});
		points.add( new double[] {-477.31683782446777, 638.0489131228334, 3204.198888097794});
		points.add( new double[] {-477.3168378244678, 638.2003777692869, 3257.0155461156787});
		points.add( new double[] {-477.3168378244678, 646.5294480641938, 3308.9120610300993});
		points.add( new double[] {-477.3168378244679, 630.3588074508518, 3392.635546035893});
		points.add( new double[] {-477.3168378244679, 634.7986892314623, 3464.3079233514595});
		points.add( new double[] {-477.3168378244679, 632.8958827540578, 3554.374096615269});
		points.add( new double[] {-477.31683782446794, 634.7986892314622, 3654.5885710919024});
		points.add( new double[] {-477.31683782446794, 639.2385710120725, 3763.6828091297557});
		points.add( new double[] {-477.316837824468, 637.335764534668, 3898.1478001996684});
		points.add( new double[] {-477.31683782446805, 639.6951671105817, 4039.9276357506815});
		points.add( new double[] {-477.31683782446805, 634.6160793693912, 4145.054661831035});
		points.add( new double[] {-477.31683782446805, 627.3106759120082, 4293.7718036420465});
		points.add( new double[] {-477.31683782446817, 624.7330191978368, 4581.005298360062});
		points.add( new double[] {-477.3168378244682, 625.7661762801172, 4937.44449174682});
		points.add( new double[] {-477.3168378244683, 625.7661762801171, 5198.833233563777});
		points.add( new double[] {-477.3168378244682, 617.9985310659346, 5428.964323485565});
		points.add( new double[] {-477.3168378244683, 609.8162096483676, 5694.889769556485});
		points.add( new double[] {-477.31683782446834, 620.044111420326, 5903.538965704438});
		points.add( new double[] {-477.31683782446834, 624.1352721291092, 6149.008608231441});
		points.add( new double[] {-477.3168378244684, 611.861790002759, 6365.84012579696});
		points.add( new double[] {-477.3168378244643, 525.9474151183076, 6748.363652068207});
		points.add( new double[] {-477.31683782446856, 505.4916115743907, 7032.699321328652});
		points.add( new double[] {-477.31683782446845, 486.56164149054666, 7320.860283816299});
		points.add( new double[] {-477.31683782446873, 477.79515734168695, 7513.72293509121});
		points.add( new double[] {-477.31683782446856, 471.01649478933024, 7758.743995814347});
		points.add( new double[] {-477.31683782446805, 467.9932014761057, 8025.146762321525});
		points.add( new double[] {-477.31683782446805, 464.9403143650967, 8292.274384534812});
		points.add( new double[] {-477.3168378244681, 467.9932014761057, 8539.55824052654});
		points.add( new double[] {-477.3168378244681, 466.46675792060114, 8812.791636961843});
		points.add( new double[] {-477.3168378244682, 471.49183806641145, 9138.164839860267});
		points.add( new double[] {-477.3168378244682, 492.4836576082455, 9488.028498890832});
		points.add( new double[] {-477.3168378244683, 506.47820396946804, 9837.892157921395});
		points.add( new double[] {-477.3168378244683, 516.9741137403848, 10110.785811965236});
		points.add( new double[] {-477.31683782446834, 544.9632064628299, 10495.635836898855});
		points.add( new double[] {-477.31683782446834, 551.9604796434412, 10883.984498422782});
		points.add( new double[] {-477.3168378244684, 558.9577528240525, 11223.35224768243});
		points.add( new double[] {-477.31683782446845, 573.2185652399893, 11545.384881483173});
		points.add( new double[] {-477.3168378244685, 584.3509753319169, 11792.153305187567});
		points.add( new double[] {-477.3168378244685, 586.2063770139048, 12076.029762531722});
		points.add( new double[] {-477.31683782446856, 584.3509753319169, 12348.773809783948});
		points.add( new double[] {-477.31683782446856, 591.7725820598686, 12651.20428394798});
		points.add( new double[] {-477.3168378244686, 599.1941887878204, 12907.249716062315});
		points.add( new double[] {-477.3168378244687, 591.7725820598686, 13142.885729674783});
		*/
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

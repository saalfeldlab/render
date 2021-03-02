package org.janelia.render.client.solver.interactive;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Function;

import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.solver.visualize.RenderTools;

import bdv.util.BdvStackSource;
import net.imglib2.Interval;
import net.imglib2.cache.Invalidate;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;

public class Untwist
{
	public static class Untwisting implements Function<Integer, AffineTransform2D>
	{
		AffineTransform2D identity = new AffineTransform2D();
		HashMap< Integer, AffineTransform2D > transforms = null;

		public void setRotation(
				final ArrayList<Pair<Integer, double[]>> positionA,
				final ArrayList<Pair<Integer, double[]>> positionB )
		{
			transforms = new HashMap<>();

			//TODO: compute final transforms
			/*
			positions.forEach( p -> {
				final AffineTransform2D t = new AffineTransform2D();
				t.translate( p.getB() );
				transforms.put( p.getA(), t.inverse() );
			});*/
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

	public static void main( String[] args ) throws IOException
	{
		String baseUrl = "http://tem-services.int.janelia.org:8080/render-ws/v1";
		String owner = "Z0720_07m_VNC"; //"flyem";
		String project = "Sec32"; //"Z0419_25_Alpha3";
		String stack = "v1_acquire_trimmed_sp1"; //"v1_acquire_sp_nodyn_v2";

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
		final Untwisting untwisting = new Untwisting();
		final ArrayList< Invalidate<?> > caches = new ArrayList<>();

		List< double[] > pointsA = new ArrayList<>();
		List< double[] > pointsB = new ArrayList<>();

		pointsA.add( new double[] {18180.08737355983, 1044.0922756053733, -62.756698404699364});
		pointsA.add( new double[] {18180.08737355983, 1475.0200175143727, 749.2973265866995});
		pointsA.add( new double[] {18189.853698805466, 1724.0546629692826, 1682.3963054607357});
		pointsA.add( new double[] {18216.26065146877, 1791.5061812018662, 4593.851412228383});
		pointsA.add( new double[] {18026.55565070638, 2470.9690334880047, 6412.092765652576});
		pointsA.add( new double[] {18026.55565070639, 3128.7214768764475, 7769.737739455516});
		pointsA.add( new double[] {18026.55565070639, 4020.1983406595014, 9125.525469792246});
		pointsA.add( new double[] {18044.623199414567, 4496.445482984773, 10431.538583396674});
		pointsA.add( new double[] {18069.563192165682, 4781.255819355375, 12703.129389095324});

		pointsB.add( new double[] {8180.08737355983, 1044.0922756053733-200, -62.756698404699364});
		pointsB.add( new double[] {8180.08737355983, 1475.0200175143727-200, 749.2973265866995});
		pointsB.add( new double[] {8189.853698805466, 1724.0546629692826-200, 1682.3963054607357});
		pointsB.add( new double[] {8216.26065146877, 1791.5061812018662-200, 4593.851412228383});
		pointsB.add( new double[] {8026.55565070638, 2470.9690334880047-200, 6412.092765652576});
		pointsB.add( new double[] {8026.55565070639, 3128.7214768764475-200, 7769.737739455516});
		pointsB.add( new double[] {8026.55565070639, 4020.1983406595014-200, 9125.525469792246});
		pointsB.add( new double[] {8044.623199414567, 4496.445482984773-200, 10431.538583396674});
		pointsB.add( new double[] {8069.563192165682, 4781.255819355375-200, 12703.129389095324});

		BdvStackSource<?> bdv = RenderTools.renderMultiRes(
				ipCache, baseUrl, owner, project, stack, interval, null, numRenderingThreads, numFetchThreads,
				untwisting, caches );
		bdv.setDisplayRange( 0, 256 );

		if ( pointsA != null && pointsB != null && pointsA.size() > 0 && pointsB.size() > 0 )
		{
			for ( final double[] p : pointsA )
				System.out.println( Util.printCoordinates( p ) );

			System.out.println();

			for ( final double[] p : pointsB )
				System.out.println( Util.printCoordinates( p ) );

			new VisualizeSegmentedLine( bdv, pointsA, Color.yellow, Color.yellow.darker(), null ).install();
			new VisualizeSegmentedLine( bdv, pointsB, Color.yellow, Color.yellow.darker(), null ).install();
		}

	}
}

package org.janelia.render.client.solver.interactive;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.alignment.util.ZFilter;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.solver.MinimalTileSpec;
import org.janelia.render.client.solver.SerializableValuePair;
import org.janelia.render.client.solver.SolveItem;
import org.janelia.render.client.solver.SolveTools;
import org.janelia.render.client.solver.visualize.RenderTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import mpicbg.models.AffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.Tile;
import mpicbg.models.TranslationModel2D;
import net.imglib2.Interval;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.position.FunctionRealRandomAccessible;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;

public class Unbend
{
	public static void assembleMatches()
	{
		
	}
	public static void main( String[] args ) throws IOException
	{
		String baseUrl = "http://tem-services.int.janelia.org:8080/render-ws/v1";
		String owner = "Z0720_07m_VNC"; //"flyem";
		String project = "Sec32"; //"Z0419_25_Alpha3";
		String stack = "v1_acquire_trimmed_sp1"; //"v1_acquire_sp_nodyn_v2";
		String matchCollection = "Sec32_v1";

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

		Double minZ = 5000.0;
		Double maxZ = 9999.0;

		RenderDataClient client = new RenderDataClient(baseUrl, owner, project );
		final List<SectionData> allSectionDataList =
				client.getStackSectionData( stack, null, null );

		ArrayList< Pair< String, Double > > pGroupList = new ArrayList<>(allSectionDataList.size());
		final ZFilter zFilter = new ZFilter(minZ,maxZ,null);
	
		final HashMap< String, Double > sectionIds = new HashMap<>();
		for ( final SectionData data : allSectionDataList )
		{
			if ( zFilter.accept( data.getZ() ) )
			{
				final String sectionId = data.getSectionId();
				final double z = data.getZ().doubleValue();

				if ( !sectionIds.containsKey( sectionId ) )
					sectionIds.put( sectionId, z );
			}
		}

		for ( final String entry : sectionIds.keySet() )
			pGroupList.add( new SerializableValuePair< String, Double >( entry, sectionIds.get( entry ) ) );

		Collections.sort( pGroupList, new Comparator< Pair< String, Double > >()
		{
			@Override
			public int compare( final Pair< String, Double > o1, final Pair< String, Double > o2 )
			{
				return o1.getB().compareTo( o2.getB() );
			}
		} );

		if (pGroupList.size() == 0)
			throw new IllegalArgumentException("stack " + stack + " does not contain any sections with the specified z values");

		RenderDataClient matchDataClient = new RenderDataClient( baseUrl, owner, matchCollection );

		final Map<String, List<Double>> sectionIdToZMap = new HashMap<>();
		final Map<Double, ResolvedTileSpecCollection> zToTileSpecsMap = new HashMap<>();

		allSectionDataList.forEach(sd ->
		{
			final Double z = sd.getZ();
			if ((z != null) && (z.compareTo(minZ) >= 0) && (z.compareTo(maxZ) <= 0))
			{
				final List<Double> zListForSection = sectionIdToZMap.computeIfAbsent(
						sd.getSectionId(), zList -> new ArrayList<Double>());

				zListForSection.add(sd.getZ());
			}
		});

		//final HashMap< Integer, AffineModel2D > renderTransforms = new HashMap<>();
		//final HashMap< Integer, double[] > cm = new HashMap<>();
		HashMap< Integer, TranslationModel2D > deltaCM = null;

		for ( final Pair< String, Double > pGroupPair : pGroupList )
		{
			final String pGroupId = pGroupPair.getA();

			final int z0 = (int)Math.round( pGroupPair.getB() );
			System.out.println( "z0=" + z0 );
			// init with empty model for the first z-plane
			if ( deltaCM == null )
			{
				deltaCM = new HashMap<Integer, TranslationModel2D>();
				deltaCM.put( z0, new TranslationModel2D() );
			}

			final List<CanvasMatches> matches = matchDataClient.getMatchesWithPGroupId(pGroupId, false);

			int z1 = Integer.MAX_VALUE;

			for (final CanvasMatches match : matches)
			{
				final String qGroupId = match.getqGroupId();

				if (pGroupId.equals(qGroupId ) )
					continue;

				final String qId = match.getqId();
				final TileSpec qTileSpec = SolveTools.getTileSpec(sectionIdToZMap, zToTileSpecsMap, client, stack, qGroupId, qId);

				if ((qTileSpec == null))
					continue;

				z1 = Math.min( z1, (int)Math.round( qTileSpec.getZ() ) );
			}

			final ArrayList< double[] > p = new ArrayList<>(), q = new ArrayList<>();

			for (final CanvasMatches match : matches)
			{
				final String pId = match.getpId();
				final String qGroupId = match.getqGroupId();
				final String qId = match.getqId();

				final TileSpec pTileSpec = SolveTools.getTileSpec(sectionIdToZMap, zToTileSpecsMap, client, stack, pGroupId, pId);
				final TileSpec qTileSpec = SolveTools.getTileSpec(sectionIdToZMap, zToTileSpecsMap, client, stack, qGroupId, qId);

				if ((pTileSpec == null) || (qTileSpec == null))
					continue;

				if ( pId.contains( "0-0-1" ) && qId.contains( "0-0-1" ) && Math.round( qTileSpec.getZ() ) == z1 )
				{
					System.out.println( pId + ", " + qId );
					// compute the deltaX and deltaY between the correspondences
					final AffineModel2D mP = SolveTools.loadLastTransformFromSpec( pTileSpec );
					final AffineModel2D mQ = SolveTools.loadLastTransformFromSpec( qTileSpec );
					final double[][] ps = match.getMatches().getPs();
					final double[][] qs = match.getMatches().getQs();

					for ( int i = 0; i < ps[ 0 ].length; ++i )
					{
						double[] tmpP = new double[ 2 ];
						tmpP[ 0 ] = ps[ 0 ][ i ];
						tmpP[ 1 ] = ps[ 1 ][ i ];
						mP.applyInPlace( tmpP );
						p.add( tmpP );

						double[] tmpQ = new double[ 2 ];
						tmpQ[ 0 ] = qs[ 0 ][ i ];
						tmpQ[ 1 ] = qs[ 1 ][ i ];
						mQ.applyInPlace( tmpQ );
						q.add( tmpQ );
					}
				}
			}

			double[] cmP = new double[ 2 ];
			double[] cmQ = new double[ 2 ];

			for ( int i = 0; i < p.size(); ++i )
			{
				cmP[ 0 ] += p.get( i )[ 0 ];
				cmP[ 1 ] += p.get( i )[ 1 ];

				cmQ[ 0 ] += q.get( i )[ 0 ];
				cmQ[ 1 ] += q.get( i )[ 1 ];
			}

			cmP[ 0 ] /= (double)p.size();
			cmP[ 1 ] /= (double)p.size();

			cmQ[ 0 ] /= (double)p.size();
			cmQ[ 1 ] /= (double)p.size();

			double d[] = new double[ 2 ];

			// pre-concantenate to previous differential transform
			TranslationModel2D n = deltaCM.get( z0 );
			n.applyInPlace( d );

			d[ 0 ] += cmQ[ 0 ] - cmP[ 0 ];
			d[ 1 ] += cmQ[ 1 ] - cmP[ 1 ];

			System.out.println( z1 + ": " + (cmQ[ 0 ] - cmP[ 0 ]) + ", " + (cmQ[ 1 ] - cmP[ 1 ]));
			TranslationModel2D t = new TranslationModel2D();
			t.set( d[ 0 ], d[ 1 ] );
			deltaCM.put( z1, t );
			
			//cm.put( z, cmQ );
			//renderTransforms.put( z, mQ );

		}

		final ArrayList< Integer > zs = new ArrayList<Integer>( deltaCM.keySet() );
		Collections.sort( zs );

		double tmp[] = new double[ 2 ];
		for ( final int z : zs )
			System.out.println( z + ": " + Util.printCoordinates( deltaCM.get( z ).apply( tmp ) ) );

		//System.exit( 0 );
		BdvStackSource<?> bdv = RenderTools.renderMultiRes(
				ipCache, baseUrl, owner, project, stack, interval, null, numRenderingThreads, numFetchThreads );
		bdv.setDisplayRange( 0, 256 );

		final double x = 18300;
		final double y = 1500;
		final double sigma = 100;
		final double two_sq_sigma = 2 * sigma * sigma;
		final double r2 = Math.pow( sigma * 4, 2 );

		final HashMap< Integer, TranslationModel2D > d = deltaCM;

		FunctionRealRandomAccessible<DoubleType> overlay =
				new FunctionRealRandomAccessible<>(
						3,
						(l,t) -> {
							final int z = (int)Math.round( l.getDoublePosition( 2 ) );
							final TranslationModel2D m = d.get( z );
	
							if ( m != null )
							{
								double[] p = new double[] { x, y };
								m.applyInPlace( p );
								final double dx = (l.getDoublePosition( 0 ) - p[ 0 ]);
								final double dy = (l.getDoublePosition( 1 ) - p[ 1 ]);
								final double sqDist = dx*dx + dy*dy;
	
								if ( sqDist <= r2 )
									t.set( 255*Math.exp( -sqDist / two_sq_sigma ) );
								else
									t.set( 0 );
							}
							else
							{
								t.set( 0 );
							}
						}, DoubleType::new );

		bdv = BdvFunctions.show( overlay, interval, "centerline", BdvOptions.options().addTo( bdv ) );
		bdv.setColor( new ARGBType( ARGBType.rgba( 255, 0, 0, 0 )));
		bdv.setDisplayRange( 0, 255 );



	}
}

package org.janelia.render.client.solver.interactive;

import java.io.IOException;
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

		Double minZ = 1650.0;
		Double maxZ = 1750.0;

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
				return o1.getA().compareTo( o2.getA() );
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

		final HashMap< Integer, Model<?> > transforms = new HashMap<>();

		for ( final Pair< String, Double > pGroupPair : pGroupList )
		{
			final String pGroupId = pGroupPair.getA();

			//System.out.println("connecting tiles with pGroupId " + pGroupId);

			final List<CanvasMatches> matches = matchDataClient.getMatchesWithPGroupId(pGroupId, false);

			for (final CanvasMatches match : matches)
			{
				final String pId = match.getpId();
				final String qGroupId = match.getqGroupId();
				final String qId = match.getqId();

				final TileSpec pTileSpec = SolveTools.getTileSpec(sectionIdToZMap, zToTileSpecsMap, client, stack, pGroupId, pId);
				final TileSpec qTileSpec = SolveTools.getTileSpec(sectionIdToZMap, zToTileSpecsMap, client, stack, qGroupId, qId);

				if ((pTileSpec == null) || (qTileSpec == null))
				{
					//System.out.println( "ignoring pair ({}, {}) because one or both tiles are missing from stack {}" +  pId + "," + qId + "," + stack);
					continue;
				}


				if ( pId.contains("0-0-2") && qId.contains( "0-0-2" ) && Math.abs( pTileSpec.getZ() - qTileSpec.getZ() ) < 1.5 )
				{
					final int z = (int)Math.round( pTileSpec.getZ() );

					if ( !transforms.containsKey( z ) )
					{
						final AffineModel2D mP = SolveTools.loadLastTransformFromSpec( pTileSpec );
						final double[][] ps = match.getMatches().getPs();

						double[] cm = new double[ 2 ];

						for ( int i = 0; i < ps[ 0 ].length; ++i )
						{
							cm[ 0 ] += ps[ 0 ][ i ];
							cm[ 1 ] += ps[ 1 ][ i ];
						}

						cm[ 0 ] /= (double)ps[ 0 ].length;
						cm[ 1 ] /= (double)ps[ 0 ].length;

						mP.applyInPlace( cm );
						TranslationModel2D t = new TranslationModel2D();
						t.set( cm[ 0 ], cm[ 1 ] );
						transforms.put( z, t );
					}
						
					//if ( !transforms.containsKey( z ) )
					//	transforms.put( z, SolveTools.loadLastTransformFromSpec( pTileSpec ) );
				}
			}

		}

		BdvStackSource<?> bdv = RenderTools.renderMultiRes(
				ipCache, baseUrl, owner, project, stack, interval, null, numRenderingThreads, numFetchThreads );
		bdv.setDisplayRange( 0, 256 );

		final double x = 0;
		final double y = 0;
		final double sigma = 100;
		final double two_sq_sigma = 2 * sigma * sigma;
		final double r2 = Math.pow( sigma * 4, 2 );

		FunctionRealRandomAccessible<DoubleType> overlay =
				new FunctionRealRandomAccessible<>(
						3,
						(l,t) -> {
							final int z = (int)Math.round( l.getDoublePosition( 2 ) );
							final Model<?> m = transforms.get( z );
	
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

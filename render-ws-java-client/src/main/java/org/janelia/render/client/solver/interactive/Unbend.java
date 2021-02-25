package org.janelia.render.client.solver.interactive;

import java.awt.Color;
import java.io.IOException;
import java.util.List;

import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.solver.visualize.RenderTools;

import bdv.util.BdvStackSource;
import net.imglib2.Interval;

public class Unbend
{
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

		BdvStackSource<?> bdv = RenderTools.renderMultiRes(
				ipCache, baseUrl, owner, project, stack, interval, null, numRenderingThreads, numFetchThreads );
		bdv.setDisplayRange( 0, 256 );

		InteractiveSegmentedLine line = new InteractiveSegmentedLine( bdv );
		List< double[] > points = line.getResult();

		if ( points != null && points.size() > 0 )
			new VisualizeSegmentedLine( bdv, points, Color.yellow, Color.yellow.darker(), null ).install();


	}
}

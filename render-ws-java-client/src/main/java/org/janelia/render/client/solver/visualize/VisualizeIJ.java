package org.janelia.render.client.solver.visualize;

import java.io.IOException;

import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.ImageProcessorCache;

import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;
import net.imglib2.Interval;
import net.imglib2.util.Util;

public class VisualizeIJ
{

	public static void main(final String[] args) throws IOException
	{
		final String baseUrl = "http://tem-services.int.janelia.org:8080/render-ws/v1";
		final String owner = "Z0720_07m_BR"; //"flyem";
		final String project = "Sec24"; //"Z0419_25_Alpha3";
		final String stack = "v3_acquire_trimmed_align_adaptive"; //"v1_acquire_sp_nodyn_v2";

		final StackMetaData meta = RenderTools.openStackMetaData(baseUrl, owner, project, stack);
		
		final int[] ds = RenderTools.availableDownsamplings( meta );
		final Interval interval = RenderTools.stackBounds(meta);

		System.out.println( Util.printCoordinates( ds ) );
		System.out.println( Util.printInterval( interval ) );

		final boolean recordStats = true;

		// only cache original imageProcessors if you don't have mipmaps
		final boolean cacheOriginalsForDownSampledImages = false;
		// make imageProcessor cache large enough for masks and some images, but leave most RAM for BDV
		final long cachedPixels = 2000000;
		final ImageProcessorCache ipCache = new ImageProcessorCache( cachedPixels, recordStats, cacheOriginalsForDownSampledImages );

		final boolean filter = false;

		final int w = (int) interval.dimension(0);
		final int h = (int) interval.dimension(1);
		final int x = (int) interval.min(0);
		final int y = (int) interval.min(1);
		final double scale = 1.0 / 7.5;
		System.out.println( scale );

		ImageStack imagestack = null ;

		final int from = 57325-5;
		final int to= 57325+5;

		for ( int z = from; z <= to; ++z )
		{
			System.out.println( z + " ... " );
			final ImageProcessorWithMasks img1 = RenderTools.renderImage(ipCache, baseUrl, owner, project, stack, x, y, z, w, h, scale, filter);
			if ( imagestack == null )
				imagestack = new ImageStack( img1.ip.getWidth(), img1.ip.getHeight() );
			imagestack.addSlice( img1.ip );
		}

		new ImageJ();
		final ImagePlus imp1 = new ImagePlus( project + "-" + stack , imagestack );

		final Calibration cal = new Calibration();
		cal.xOrigin = -(int)interval.min(0);
		cal.yOrigin = -(int)interval.min(1);
		cal.zOrigin = -from;
		cal.pixelWidth = 1.0/scale;
		cal.pixelHeight = 1.0/scale;
		cal.pixelDepth = 1.0;
		imp1.setCalibration( cal );

		imp1.show();

	}
}

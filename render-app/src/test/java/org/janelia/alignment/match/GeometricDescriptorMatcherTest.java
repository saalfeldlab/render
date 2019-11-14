package org.janelia.alignment.match;

import ij.ImagePlus;
import ij.gui.OvalRoi;
import ij.gui.Overlay;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.process.ImageProcessor;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import mpicbg.imglib.algorithm.scalespace.DifferenceOfGaussianPeak;
import mpicbg.imglib.multithreading.SimpleMultiThreading;
import mpicbg.imglib.type.numeric.real.FloatType;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

import org.janelia.alignment.ArgbRenderer;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
import org.janelia.alignment.match.parameters.GeometricDescriptorParameters;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.janelia.alignment.util.ImageDebugUtil;
import org.janelia.alignment.util.ImageProcessorCache;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.imglib2.util.Util;

/**
 * Runs peak extraction for two tiles.
  */
public class GeometricDescriptorMatcherTest {

    static String[][] TEST_TILE_PAIRS = {
            // owner,          project, stack,        tile1,                           tile2
            { "Z1217_19m",    "Sec07", "v1_acquire", "19-02-21_105501_0-0-0.26101.0", "19-02-21_161150_0-0-0.26102.0", "cross" }, // 0. VNC Sec07, cross, mix of resin and tissue
            { "Z1217_19m",    "Sec07", "v1_acquire", "19-02-21_105501_0-0-0.26101.0", "19-02-21_105501_0-0-1.26101.0" },          // 1. VNC Sec07, montage, mostly resin overlap
            { "Z1217_19m",    "Sec07", "v1_acquire", "19-02-18_221123_0-0-1.23001.0", "19-02-18_221123_0-0-2.23001.0" },          // 2. VNC Sec07, montage, mix of resin and tissue overlap
            { "Z1217_19m",    "Sec07", "v1_acquire", "19-02-11_060620_0-0-0.14002.0", "19-02-11_060620_0-0-1.14002.0" },          // 3. VNC Sec07, montage, mostly tissue overlap
            { "Z1217_33m_BR", "Sec09", "v1_acquire", "19-08-22_095727_0-0-0.500.0",   "19-08-22_095727_0-0-1.500.0"   },          // 4. BR  Sec09, montage, mostly resin overlap
            { "Z1217_33m_BR", "Sec09", "v1_acquire", "19-08-26_141500_0-0-0.5500.0",  "19-08-26_141500_0-0-1.5500.0"  },          // 5. BR  Sec09, montage, mix of resin and tissue overlap
            { "Z1217_33m_BR", "Sec09", "v1_acquire", "19-08-30_172535_0-0-1.10500.0", "19-08-30_172535_0-0-2.10500.0" }           // 6. BR  Sec09, montage, mostly tissue overlap
    };

    @Test
    public void testNothing() {
        Assert.assertTrue(true);
    }

    public static void main(final String[] args) {

       // -------------------------------------------------------------------
        // NOTES:
        //
        //   1. make sure /groups/flyem/data is mounted
        //      -- on Mac, after mount need to ln -s /Volumes/flyemdata /groups/flyem/data
        //
        //   2. update testTilePairIndex (and possibly TEST_TILE_PAIRS)

        // -------------------------------------------------------------------
        // setup test parameters ...

        final GeometricDescriptorParameters gdParameters = getInitialDescriptorParameters();
        gdParameters.fullScaleBlockRadius = null;
        gdParameters.fullScaleNonMaxSuppressionRadius = 120.0;

        final CanvasPeakExtractor extractor = new CanvasPeakExtractor(gdParameters);

        final MatchDerivationParameters matchDerivationParameters = getMatchFilterParameters();
        final CanvasPeakMatcher peakMatcher = new CanvasPeakMatcher(gdParameters,
                                                                    matchDerivationParameters);

        final double peakRenderScale = 0.25;

        final int testTilePairIndex = 0;

        final String owner = TEST_TILE_PAIRS[testTilePairIndex][0];
        final String project = TEST_TILE_PAIRS[testTilePairIndex][1];
        final String stack = TEST_TILE_PAIRS[testTilePairIndex][2];
        final String tileId1 = TEST_TILE_PAIRS[testTilePairIndex][3];
        final String tileId2 = TEST_TILE_PAIRS[testTilePairIndex][4];

        final Integer clipSize = TEST_TILE_PAIRS[testTilePairIndex].length > 5 ? null : 500;

        // -------------------------------------------------------------------
        // run test ...

        final RenderParameters renderParametersTile1 =
                getRenderParametersForTile(owner, project, stack, tileId1, peakRenderScale, false, clipSize, MontageRelativePosition.LEFT);
        final RenderParameters renderParametersTile2 =
                getRenderParametersForTile(owner, project, stack, tileId2, peakRenderScale, false, clipSize, MontageRelativePosition.RIGHT);

        final BufferedImage image1 = renderImage(renderParametersTile1);
        final BufferedImage image2 = renderImage(renderParametersTile2);

        List<DifferenceOfGaussianPeak<FloatType>> canvasPeaks1 = extractor.extractPeaksFromImage(image1);
        List<DifferenceOfGaussianPeak<FloatType>> canvasPeaks2 = extractor.extractPeaksFromImage(image2);

        LOG.debug( "#detections: " + canvasPeaks1.size() + " & " + canvasPeaks2.size() );

        //canvasPeaks1 = GeometricDescriptorSIFTMatcherTest.thinOut( canvasPeaks1, 0, 10, false );
        //canvasPeaks2 = GeometricDescriptorSIFTMatcherTest.thinOut( canvasPeaks2, 0, 10, false );

        //LOG.debug( "#detections after thinning: " + canvasPeaks1.size() + " & " + canvasPeaks2.size() );

        canvasPeaks1 = extractor.nonMaximalSuppression(canvasPeaks1, peakRenderScale);
        canvasPeaks2 = extractor.nonMaximalSuppression(canvasPeaks2, peakRenderScale);

        LOG.debug( "#detections after thinning: " + canvasPeaks1.size() + " & " + canvasPeaks2.size() );

        final ImagePlus ip1 = new ImagePlus("peak_" + tileId1, image1);
        final ImagePlus ip2 = new ImagePlus("peak_" + tileId2, image2);

        ImageDebugUtil.setPeakPointRois(canvasPeaks1, ip1);
        ImageDebugUtil.setPeakPointRois(canvasPeaks2, ip2);

        ip1.show();
        ip2.show();

        //SimpleMultiThreading.threadHaltUnClean();

        // important, we need to use the adjusted parameters here as well
        final CanvasMatchResult result = peakMatcher.deriveMatchResult(canvasPeaks1, canvasPeaks2);

        final List<PointMatch> inliers = result.getInlierPointMatchList();

        final ImagePlus ipnew1 = new ImagePlus("match_" + tileId1, image1);
        final ImagePlus ipnew2 = new ImagePlus("match_" + tileId2, image2);

        ImageDebugUtil.setPointMatchRois(inliers, ipnew1, ipnew2);

        final ArrayList< Point > convexHull1 = convexHull( ImageDebugUtil.getSourcePoints( inliers ) );
        final ArrayList< Point > convexHull2 = convexHull( ImageDebugUtil.getTargetPoints( inliers ) );

        if ( convexHull1 == null || convexHull2 == null )
        {
        	LOG.debug( "No convex hull found" );
        }
        else
        {
        	PolygonRoi r1 = createPolygon( convexHull1 );
        	PolygonRoi r2 = createPolygon( convexHull2 );

        	final long sqArea1 = sqArea( ipnew1.getProcessor() );
        	final long sqArea2 = sqArea( ipnew2.getProcessor() );

        	final double area1 = polygonArea( convexHull1 ); //areaBruteForce( ipnew1.getProcessor(), r1 );
        	final double area2 = polygonArea( convexHull2 ); //areaBruteForce( ipnew2.getProcessor(), r2 );

        	final double relArea1 = area1/sqArea1;
        	final double relArea2 = area2/sqArea2;

        	final double[] c1 = centerofmassBruteForce( r1 );
        	final double[] c2 = centerofmassBruteForce( r2 );

        	LOG.debug( "Convex hull 1: " + convexHull1.size() + ", area=" + area1 + "/" + sqArea1 + " (" + Math.round( 100.0*relArea1 ) + "%)" );
        	LOG.debug( "center of mass 1: " + Util.printCoordinates( c1 ) );
        	LOG.debug( "" );
        	LOG.debug( "Convex hull 2: " + convexHull2.size() + ", area=" + area2 + "/" + sqArea2 + " (" + Math.round( 100.0*relArea2 ) + "%)" );
        	LOG.debug( "center of mass 2: " + Util.printCoordinates( c2 ) );

        	Overlay o1 = new Overlay();
        	o1.add( r1 );
        	o1.add( new OvalRoi( c1[ 0 ] - 15, c1[ 1 ] - 15, 30, 30 ) );
        	ipnew1.setOverlay( o1 );

        	Overlay o2 = new Overlay();
        	o2.add( r2 );
        	o2.add( new OvalRoi( c2[ 0 ] - 15, c2[ 1 ] - 15, 30, 30 ) );
        	ipnew2.setOverlay( o2 );
        }

        ipnew1.show();
        ipnew2.show();

        SimpleMultiThreading.threadHaltUnClean();

        // -------------------------------------------------------------------
        // display results ...
    }


    // from: https://www.mathopenref.com/coordpolygonarea2.html
    static double polygonArea( final List< Point > convexHull ) 
    {
		final int numPoints = convexHull.size();
		double area = 0;         // Accumulates area in the loop
		int j = numPoints-1;  // The last vertex is the 'previous' one to the first
		
		for ( int i=0; i<numPoints; i++)
		{
			area += ( convexHull.get( j ).getL()[ 0 ] + convexHull.get( i ).getL()[ 0 ] ) * (convexHull.get( j ).getL()[ 1 ] - convexHull.get( i ).getL()[ 1 ] );
			j = i;  //j is previous vertex to i
		}

		return Math.abs( area/2 );
    }

    static PolygonRoi createPolygon( final List< Point > points )
    {
    	final float[] xPoints = new float[ points.size() ];
    	final float[] yPoints = new float[ points.size() ];

    	for ( int i = 0; i < xPoints.length; ++i )
    	{
    		xPoints[ i ] = (float)points.get( i ).getL()[ 0 ];
    		yPoints[ i ] = (float)points.get( i ).getL()[ 1 ];
    	}

    	return new PolygonRoi( xPoints, yPoints, Roi.POLYGON );
    }

    // from: https://www.geeksforgeeks.org/convex-hull-set-1-jarviss-algorithm-or-wrapping/

    // To find orientation of ordered triplet (p, q, r). 
    // The function returns following values 
    // 0 --> p, q and r are colinear 
    // 1 --> Clockwise 
    // 2 --> Counterclockwise 
    public static int orientation( final Point p, final Point q, final Point r )
    { 
        double val = (q.getL()[ 1 ] - p.getL()[ 1 ]) * (r.getL()[ 0 ] - q.getL()[ 0 ]) -
                     (q.getL()[ 0 ] - p.getL()[ 0 ]) * (r.getL()[ 1 ] - q.getL()[ 1 ] );
       
        if (val == 0) return 0;  // collinear 
        return (val > 0)? 1: 2; // clock or counterclock wise 
    }

    // Prints convex hull of a set of n points. 
    public static ArrayList<Point> convexHull( final List< Point > points )
    {
    	final int size = points.size();

        // There must be at least 3 points 
        if ( size < 3 ) return null;
       
        // Initialize Result 
        ArrayList<Point> hull = new ArrayList<>(); 
       
        // Find the leftmost point 
        int l = 0;
        for (int i = 1; i < size; i++) 
            if ( points.get( i ).getL()[ 0 ] <  points.get( l ).getL()[ 0 ] )
                l = i;

        // Start from leftmost point, keep moving  
        // counterclockwise until reach the start point 
        // again. This loop runs O(h) times where h is 
        // number of points in result or output. 
        int p = l, q; 
        do
        { 
            // Add current point to result 
            hull.add(points.get( p ) );

            // Search for a point 'q' such that  
            // orientation(p, x, q) is counterclockwise  
            // for all points 'x'. The idea is to keep  
            // track of last visited most counterclock- 
            // wise point in q. If any point 'i' is more  
            // counterclock-wise than q, then update q. 
            q = (p + 1) % size; 
              
            for (int i = 0; i < size; i++) 
            { 
               // If i is more counterclockwise than  
               // current q, then update q 
               if (orientation(points.get( p ), points.get( i ), points.get( q ) ) == 2) 
                   q = i;
            }

            // Now q is the most counterclockwise with 
            // respect to p. Set p as q for next iteration,  
            // so that q is added to result 'hull' 
            p = q; 
       
        } while (p != l);  // While we don't come to first  
                           // point 

        return hull;
    }

    static long sqArea( final ImageProcessor r )
    {
    	return ( r.getWidth() ) * ( r.getHeight() );
    }

    static long areaBruteForce( final ImageProcessor r, final PolygonRoi roi )
    {
    	return areaBruteForce( new Rectangle( 0, 0, r.getWidth(), r.getHeight() ), roi );
    }

    static long areaBruteForce( final Rectangle r, final PolygonRoi roi )
    {
    	long area = 0;

    	for ( int y = r.y; y < r.height + r.y; ++y )
    		for ( int x = r.x; x < r.width + r.x; ++x )
    			if ( roi.contains( x, y ) )
    				++area;

    	return area;
    }

    //TODO: proper center of mass computation
    static double[] centerofmassBruteForce( final PolygonRoi roi )
    {
    	final Rectangle r = roi.getBounds();

    	long cx = 0, cy = 0;
    	long count = 0;

    	for ( int y = r.y; y < r.height + r.y; ++y )
    		for ( int x = r.x; x < r.width + r.x; ++x )
    			if ( roi.contains( x, y ) )
    			{
    				cx += x;
    				cy += y;
    				++count;
    			}

    	return new double[] { (double)cx / (double)count, (double)cy /(double)count };
    }

    static GeometricDescriptorParameters getInitialDescriptorParameters() {

        final GeometricDescriptorParameters parameters = new GeometricDescriptorParameters();

        parameters.numberOfNeighbors = 3;
        parameters.redundancy = 1;
        parameters.significance = 2.0;
        parameters.sigma = 2.04;
        parameters.threshold = 0.008;
        parameters.localization = GeometricDescriptorParameters.LocalizationFitType.NONE;

        parameters.lookForMinima = true;
        parameters.lookForMaxima = false;

        parameters.fullScaleBlockRadius = 300.0;
        parameters.fullScaleNonMaxSuppressionRadius = 60.0;

        return parameters;
    }

    static MatchDerivationParameters getMatchFilterParameters() {

        final MatchDerivationParameters matchFilterParameters = new MatchDerivationParameters();

        matchFilterParameters.matchModelType = ModelType.RIGID;
        matchFilterParameters.matchIterations = 1000;
        matchFilterParameters.matchMaxEpsilon = 20.0f;
        matchFilterParameters.matchMinInlierRatio = 0.0f;
        matchFilterParameters.matchMinNumInliers = 4;
        matchFilterParameters.matchMaxTrust = 3.0;
        matchFilterParameters.matchFilter = MatchFilter.FilterType.SINGLE_SET;

        return matchFilterParameters;
    }

    static RenderParameters getRenderParametersForTile(final String owner,
                                                       final String project,
                                                       final String stack,
                                                       final String tileId,
                                                       final double renderScale,
                                                       final boolean filter,
                                                       final Integer clipSize,
                                                       final MontageRelativePosition relativePosition) {
        final String baseTileUrl = "http://renderer-dev.int.janelia.org:8080/render-ws/v1/owner/" + owner +
                                   "/project/" + project + "/stack/" + stack + "/tile/";
        final String urlSuffix = "/render-parameters?scale=" + renderScale;
        // TODO: add &fillWithNoise=true ?
        // TODO: add &excludeMask=true ?
        final String url = baseTileUrl + tileId + urlSuffix;

        final RenderParameters renderParameters = RenderParameters.loadFromUrl(url);
        renderParameters.setDoFilter( filter );
        renderParameters.initializeDerivedValues();

        renderParameters.validate();

        // remove mipmapPathBuilder so that we don't get exceptions when /nrs is not mounted
        renderParameters.setMipmapPathBuilder(null);
        renderParameters.applyMipmapPathBuilderToTileSpecs();

        if ((clipSize != null) && (relativePosition != null)) {
            final CanvasId canvasId = new CanvasId("GROUP_ID", tileId, relativePosition);
            canvasId.setClipOffsets(renderParameters.getWidth(), renderParameters.getHeight(), clipSize, clipSize);
            renderParameters.clipForMontagePair(canvasId, clipSize, clipSize);
        }

        return renderParameters;
    }

    static BufferedImage renderImage(final RenderParameters renderParameters) {
        // RGB (the alpha channel contains a mask for the left stripe to ignore)
        final BufferedImage bufferedImage = renderParameters.openTargetImage();
        ArgbRenderer.render(renderParameters, bufferedImage, ImageProcessorCache.DISABLED_CACHE);

        // 8 bit (almost identical to RGB when converted to gray)
        //final BufferedImage bufferedImage = renderParameters.openTargetImage( BufferedImage.TYPE_BYTE_GRAY );
        //ShortRenderer.render(renderParameters, bufferedImage, ImageProcessorCache.DISABLED_CACHE);
        // TODO: Write ByteRenderer? Somehow the ShortRendered also works for 8-bit when providing a BufferedImage.TYPE_BYTE_GRAY

        // 16 bit
        //final BufferedImage bufferedImage = renderParameters.openTargetImage( BufferedImage.TYPE_USHORT_GRAY );
        //ShortRenderer.render(renderParameters, bufferedImage, ImageProcessorCache.DISABLED_CACHE);

        //new ImageJ();
        //new ImagePlus( "", bufferedImage ).show();
        //SimpleMultiThreading.threadHaltUnClean();

        return bufferedImage;
    }

    static ImageProcessorWithMasks renderProcessorWithMasks(final RenderParameters renderParameters) {
        final Renderer renderer = new Renderer(renderParameters, ImageProcessorCache.DISABLED_CACHE);
        return renderer.renderImageProcessorWithMasks();
    }

    private static final Logger LOG = LoggerFactory.getLogger(GeometricDescriptorMatcherTest.class);
}

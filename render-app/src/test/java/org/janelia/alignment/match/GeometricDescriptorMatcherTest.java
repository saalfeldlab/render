package org.janelia.alignment.match;

import ij.ImagePlus;
import ij.gui.Overlay;
import ij.gui.PolygonRoi;
import ij.gui.Roi;

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

import com.esotericsoftware.minlog.Log;

/**
 * Runs peak extraction for two tiles.
  */
public class GeometricDescriptorMatcherTest {

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
        //   2. update test parameters
        //      -- tests assume tiles are in Z1217_19m :: Sec07 :: v1_acquire  stack

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

        final String tileId1 = "19-02-21_105501_0-0-0.26101.0";
        final String tileId2 = "19-02-21_161150_0-0-0.26102.0";

        // -------------------------------------------------------------------
        // run test ...

        final RenderParameters renderParametersTile1 = getRenderParametersForTile(tileId1, peakRenderScale, false);
        final RenderParameters renderParametersTile2 = getRenderParametersForTile(tileId2, peakRenderScale, false);

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
        	LOG.debug( "Convex hull 1: " + convexHull1.size() );
        	LOG.debug( "Convex hull 2: " + convexHull2.size() );

        	PolygonRoi r1 = createPolygon( convexHull1 );
        	PolygonRoi r2 = createPolygon( convexHull2 );

        	Overlay o1 = new Overlay();
        	o1.add( r1 );
        	ipnew1.setOverlay( o1 );

        	Overlay o2 = new Overlay();
        	o2.add( r2 );
        	ipnew2.setOverlay( o2 );
        }

        ipnew1.show();
        ipnew2.show();

        SimpleMultiThreading.threadHaltUnClean();

        // -------------------------------------------------------------------
        // display results ...
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

    static RenderParameters getRenderParametersForTile(final String tileId,
                                                       final double renderScale,
                                                       final boolean filter) {
        final String baseTileUrl = "http://renderer-dev.int.janelia.org:8080/render-ws/v1/owner/Z1217_19m/project/Sec07/stack/v1_acquire/tile/";
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

package org.janelia.alignment.match;

import ij.ImageJ;
import ij.ImagePlus;
import ij.gui.PointRoi;
import ij.gui.Roi;
import ij.plugin.Duplicator;
import ij.plugin.filter.GaussianBlur;
import ij.process.ImageProcessor;
import mpicbg.imglib.multithreading.SimpleMultiThreading;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.TranslationModel2D;
import mpicbg.stitching.PairWiseStitchingImgLib;
import mpicbg.stitching.PairWiseStitchingResult;
import mpicbg.stitching.StitchingParameters;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;
import net.imglib2.util.Util;
import stitching.utils.Log;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.janelia.alignment.util.ImageProcessorCache;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Run cross correlation for two tiles.
 */
public class CrossCorrelationTest {

    // If you want to quickly see test tiles in browser, use renderer URL links.
    static String[][] TEST_TILE_PAIRS = {
            // owner,          project, stack,        tile1,                           tile2

            // http://renderer.int.janelia.org:8080/render-ws/view/tile-with-neighbors.html?tileId=20-07-12_061129_0-0-1.400.0&renderScale=0.0639968396622389&renderStackOwner=Z0620_23m_VNC&renderStackProject=Sec25&renderStack=v2_acquire&matchOwner=Z0620_23m_VNC&matchCollection=Sec25_v2
            { "Z0620_23m_VNC", "Sec25", "v2_acquire", "20-07-12_061129_0-0-0.400.0",   "20-07-12_061129_0-0-1.400.0"  },

            // http://renderer.int.janelia.org:8080/render-ws/view/tile-with-neighbors.html?tileId=20-07-13_041103_0-0-1.1800.0&renderScale=0.0639968396622389&renderStackOwner=Z0620_23m_VNC&renderStackProject=Sec25&renderStack=v2_acquire&matchOwner=Z0620_23m_VNC&matchCollection=Sec25_v2
            { "Z0620_23m_VNC", "Sec25", "v2_acquire", "20-07-13_041103_0-0-0.1800.0",  "20-07-13_041103_0-0-1.1800.0" },

            // http://renderer.int.janelia.org:8080/render-ws/view/tile-with-neighbors.html?tileId=20-07-14_093711_0-0-1.2800.0&renderScale=0.0639968396622389&renderStackOwner=Z0620_23m_VNC&renderStackProject=Sec25&renderStack=v2_acquire&matchOwner=Z0620_23m_VNC&matchCollection=Sec25_v2
            { "Z0620_23m_VNC", "Sec25", "v2_acquire", "20-07-14_093711_0-0-0.2800.0",  "20-07-14_093711_0-0-1.2800.0" },
            { "Z0620_23m_VNC", "Sec25", "v2_acquire", "20-07-14_093711_0-0-1.2800.0",  "20-07-14_093711_0-0-2.2800.0" },

            // http://renderer.int.janelia.org:8080/render-ws/view/tile-with-neighbors.html?tileId=20-07-19_024208_0-0-1.9500.0&renderScale=0.05573953808438347&renderStackOwner=Z0620_23m_VNC&renderStackProject=Sec25&renderStack=v2_acquire&matchOwner=Z0620_23m_VNC&matchCollection=Sec25_v2
            { "Z0620_23m_VNC", "Sec25", "v2_acquire", "20-07-19_024208_0-0-1.9500.0",  "20-07-19_024208_0-0-2.9500.0" },

            // http://renderer.int.janelia.org:8080/render-ws/view/tile-with-neighbors.html?tileId=20-07-19_184758_0-0-1.10500.0&renderScale=0.05573953808438347&renderStackOwner=Z0620_23m_VNC&renderStackProject=Sec25&renderStack=v2_acquire&matchOwner=Z0620_23m_VNC&matchCollection=Sec25_v2
            { "Z0620_23m_VNC", "Sec25", "v2_acquire", "20-07-19_184758_0-0-1.10500.0", "20-07-19_184758_0-0-2.10500.0"}
    };

    @Test
    public void testNothing() {
        Assert.assertTrue(true);
    }

    public static Rectangle findRectangle( final ImageProcessorWithMasks ipm )
    {
    	// TODO: assumes it is not rotated

    	final ImageProcessor ip = ipm.mask;

    	int minX = ip.getWidth();
    	int maxX = 0;

    	int minY = ip.getHeight();
    	int maxY = 0;

    	for ( int y = 0; y < ip.getHeight(); ++y )
    		for ( int x = 0; x < ip.getWidth(); ++x )
    		{
    			if ( ip.getf(x, y) >= 255 )
				{
    				minX = Math.min( minX, x );
    				maxX = Math.max( maxX, x );
    				minY = Math.min( minY, y );
    				maxY = Math.max( minY, y );
				}
    		}

    	System.out.println( "minX " + minX );
    	System.out.println( "maxX " + maxX );
    	System.out.println( "minY " + minY );
    	System.out.println( "maxY " + maxY );

    	return new Rectangle(minX, minY, maxX-minX+1, maxY-minY+1);
    	//ip.setRoi( );
    }

    public static void blur( final ImagePlus imp, final Rectangle r, final double sigma )
    {
    	imp.setRoi( r );

    	// duplicate ROI (avoid artifacts from black areas)
    	ImagePlus imp2 = (new Duplicator()).run( imp );
    	ImageProcessor proc2 = imp2.getProcessor();

    	new GaussianBlur().blurGaussian( proc2, sigma );
    	ImageProcessor proc1 = imp.getProcessor();

    	for ( int y = 0; y < imp2.getHeight(); ++y )
    		for ( int x = 0; x < imp2.getWidth(); ++x )
    			proc1.setf( x + r.x, y + r.y, proc2.getf(x, y) );
    }

    public static void main(final String[] args) {

    	new ImageJ();

       // -------------------------------------------------------------------
        // NOTES:
        //
        //   1. make sure /groups/flyem/data is mounted  ( from smb://dm11.hhmi.org/flyemdata )

        LOG.debug("starting cross correlation ...");

        // -------------------------------------------------------------------
        // setup test parameters ...

        // TODO: change this index (0 - 5) to work with a different tile pair
        final int testTilePairIndex = 0;

        final String owner = TEST_TILE_PAIRS[testTilePairIndex][0];
        final String project = TEST_TILE_PAIRS[testTilePairIndex][1];
        final String stack = TEST_TILE_PAIRS[testTilePairIndex][2];
        final String tileId1 = TEST_TILE_PAIRS[testTilePairIndex][3];
        final String tileId2 = TEST_TILE_PAIRS[testTilePairIndex][4];

        final Integer clipSize = 500;

        // TODO: setup cross correlation parameters
        final double renderScale = 0.4;

        // initial blurring (no!)
        final double sigma = 0;

        final int sizeYFull = 250;
        final int stepYFull = 5;
    	final double rThreshold = 0.8;

    	final int sizeY = Math.max( 10, (int)Math.round( sizeYFull * renderScale ) );
    	final int stepY = Math.max( 1, (int)Math.round( stepYFull * renderScale ) );

    	System.out.println( "renderscale: " + renderScale );
    	System.out.println( "sigma: " + sigma );
    	System.out.println( "rThreshold: " + rThreshold );
    	System.out.println( "sizeY: " + sizeY );
    	System.out.println( "stepY: " + stepY );

        final StitchingParameters params = new StitchingParameters();
        params.dimensionality = 2;
        params.fusionMethod = 0;
        params.fusedName = "";
        params.checkPeaks = 5;
        params.addTilesAsRois = false;
        params.computeOverlap = true;
        params.subpixelAccuracy = true;
        params.ignoreZeroValuesFusion = false;
        params.downSample = false;
        params.displayFusion = false;
        params.invertX = false;
        params.invertY = false;
        params.ignoreZStage = false;
        params.xOffset = 0.0;
        params.yOffset = 0.0;
        params.zOffset = 0.0;

        final float maxErrorFull = 1f;
        final float maxError = maxErrorFull * (float)renderScale;
        final MatchDerivationParameters matchDerivationParameters = getMatchFilterParameters( maxError );

        // -------------------------------------------------------------------
        // run test ...

        final RenderParameters renderParametersTile1 =
                GeometricDescriptorMatcherTest.getRenderParametersForTile(owner, project, stack, tileId1, renderScale, false, clipSize, MontageRelativePosition.LEFT);
        final RenderParameters renderParametersTile2 =
                GeometricDescriptorMatcherTest.getRenderParametersForTile(owner, project, stack, tileId2, renderScale, false, clipSize, MontageRelativePosition.RIGHT);

        final ImageProcessorWithMasks ipm1 = Renderer.renderImageProcessorWithMasks(renderParametersTile1, ImageProcessorCache.DISABLED_CACHE);
        final ImageProcessorWithMasks ipm2 = Renderer.renderImageProcessorWithMasks(renderParametersTile2, ImageProcessorCache.DISABLED_CACHE);

        // TODO: run cross correlation

        final ImagePlus ip1 = new ImagePlus("peak_" + tileId1, ipm1.ip);
        final ImagePlus ip2 = new ImagePlus("peak_" + tileId2, ipm2.ip);

        // ImageDebugUtil.setPeakPointRois(canvasPeaks1, ip1);

        final Rectangle r1 = findRectangle( ipm1 );
        final Rectangle r2 = findRectangle( ipm2 );

    	if ( sigma > 0 )
    	{
    		blur( ip1, r1, sigma );
    		blur( ip2, r2, sigma );
    	}

        ip1.show();
        ip2.show();

        final ImagePlus mask1 = new ImagePlus("mask_" + tileId1, ipm1.mask );
        final ImagePlus mask2 = new ImagePlus("mask_" + tileId2, ipm2.mask );

        mask1.show();
        mask2.show();

        final int startY = Math.min( r1.y, r2.y );
        final int endY = Math.max( r1.y + r1.height - 1, r2.y + r2.height - 1 );

        final int numTests = (endY-startY-sizeY+stepY+1)/stepY + Math.min( 1, (endY-startY-sizeY+stepY+1)%stepY );
        final double incY = (endY-startY-sizeY+stepY+1) / (double)numTests;

        System.out.println( numTests + " " + incY );

        final List<PointMatch> candidates = new ArrayList<PointMatch>();

        PointRoi p1Candidates = new PointRoi();
        PointRoi p2Candidates = new PointRoi();

        for ( int i = 0; i < numTests; ++i )
        {
        	final int minY = (int)Math.round( i * incY ) + startY;
        	final int maxY =  minY + sizeY - 1;

        	//System.out.println( " " + minY  + " > " + maxY );

        	final Rectangle r1PCM = new Rectangle( r1.x, minY, r1.width, maxY - minY + 1 );
        	final Rectangle r2PCM = new Rectangle( r2.x, minY, r2.width, maxY - minY + 1 );

        	mask1.setRoi( r1PCM );
            mask2.setRoi( r2PCM );

        	final PairWiseStitchingResult result = PairWiseStitchingImgLib.stitchPairwise( ip1, ip2, new Roi( r1PCM ), new Roi( r2PCM ), 1, 1, params );

        	if ( result.getCrossCorrelation() >= rThreshold )
        	{
        		System.out.println( minY  + " > " + maxY + ", shift : " + Util.printCoordinates( result.getOffset() ) + ", correlation (R)=" + result.getCrossCorrelation() );

        		double r1X = 0;
        		double r1Y = minY + sizeY / 2.0;

        		double r2X = -result.getOffset( 0 );
        		double r2Y = minY + sizeY / 2.0 - result.getOffset( 1 ); 

        		// just to place the points within the overlapping area
        		// (only matters for visualization)
        		double shiftX = 0;

        		if ( r2X < r2.x )
        			shiftX += r2.x - r2X;
        		else if ( r2X >= r2.x + r2.width )
        			shiftX -= r2X - (r2.x + r2.width);

        		r1X += shiftX;
        		r2X += shiftX;

        		final Point p1 = new Point( new double[] { r1X, r1Y } );
        		final Point p2 = new Point( new double[] { r2X, r2Y } );

        		candidates.add( new PointMatch( p1, p2 ) );

        		p1Candidates.addPoint( r1X, r1Y );
        		p2Candidates.addPoint( r2X, r2Y );
        	}
        }

        // Running RANSAC
        final MatchFilter matchFilter = new MatchFilter( matchDerivationParameters );
        final CanvasMatchResult result = matchFilter.buildMatchResult(candidates);
        final List<PointMatch> inliers = result.getInlierPointMatchList();

        // TODO: adjust for the 0.4 scaling????
        System.out.println( "ransac: " + result );
        try {
            final TranslationModel2D model = new TranslationModel2D();
			model.fit( inliers );
			System.out.println( model );
		} catch (NotEnoughDataPointsException e) {}

        // visualize result
        PointRoi p1Inliers = new PointRoi();
        PointRoi p2Inliers = new PointRoi();

        for ( final PointMatch pm : inliers )
        {
        	p1Inliers.addPoint( pm.getP1().getL()[ 0 ], pm.getP1().getL()[ 1 ] );
        	p2Inliers.addPoint( pm.getP2().getL()[ 0 ], pm.getP2().getL()[ 1 ] );
        }

        ip1.setRoi( p1Inliers );
        ip2.setRoi( p2Inliers );

        SimpleMultiThreading.threadHaltUnClean();

    }

    static MatchDerivationParameters getMatchFilterParameters( final float maxError ) {

        final MatchDerivationParameters matchFilterParameters = new MatchDerivationParameters();

        matchFilterParameters.matchModelType = ModelType.TRANSLATION;
        matchFilterParameters.matchIterations = 1000;
        matchFilterParameters.matchMaxEpsilon = maxError;
        matchFilterParameters.matchMinInlierRatio = 0.0f;
        matchFilterParameters.matchMinNumInliers = 20;
        matchFilterParameters.matchMaxTrust = 3.0;
        matchFilterParameters.matchFilter = MatchFilter.FilterType.SINGLE_SET;

        return matchFilterParameters;
    }

    private static final Logger LOG = LoggerFactory.getLogger(CrossCorrelationTest.class);
}

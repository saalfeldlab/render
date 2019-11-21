package org.janelia.alignment.match;

import ij.ImagePlus;
import ij.gui.OvalRoi;
import ij.gui.Overlay;
import ij.gui.PolygonRoi;
import ij.process.ImageProcessor;

import java.awt.Rectangle;
import java.util.List;

import mpicbg.imglib.algorithm.scalespace.DifferenceOfGaussianPeak;
import mpicbg.imglib.multithreading.SimpleMultiThreading;
import mpicbg.imglib.type.numeric.real.FloatType;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

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

        final ImageProcessorWithMasks ipm1 = Renderer.renderImageProcessorWithMasks(renderParametersTile1, ImageProcessorCache.DISABLED_CACHE);
        final ImageProcessorWithMasks ipm2 = Renderer.renderImageProcessorWithMasks(renderParametersTile2, ImageProcessorCache.DISABLED_CACHE);

        List<DifferenceOfGaussianPeak<FloatType>> canvasPeaks1 =
                extractor.extractPeaksFromImageAndMask(ipm1.ip, ipm1.mask);
        List<DifferenceOfGaussianPeak<FloatType>> canvasPeaks2 =
                extractor.extractPeaksFromImageAndMask(ipm2.ip, ipm2.mask);

        LOG.debug( "#detections: " + canvasPeaks1.size() + " & " + canvasPeaks2.size() );

        //canvasPeaks1 = GeometricDescriptorSIFTMatcherTest.thinOut( canvasPeaks1, 0, 10, false );
        //canvasPeaks2 = GeometricDescriptorSIFTMatcherTest.thinOut( canvasPeaks2, 0, 10, false );

        //LOG.debug( "#detections after thinning: " + canvasPeaks1.size() + " & " + canvasPeaks2.size() );

        canvasPeaks1 = extractor.nonMaximalSuppression(canvasPeaks1, peakRenderScale);
        canvasPeaks2 = extractor.nonMaximalSuppression(canvasPeaks2, peakRenderScale);

        LOG.debug( "#detections after thinning: " + canvasPeaks1.size() + " & " + canvasPeaks2.size() );

        final ImagePlus ip1 = new ImagePlus("peak_" + tileId1, ipm1.ip);
        final ImagePlus ip2 = new ImagePlus("peak_" + tileId2, ipm2.ip);

        ImageDebugUtil.setPeakPointRois(canvasPeaks1, ip1);
        ImageDebugUtil.setPeakPointRois(canvasPeaks2, ip2);

        ip1.show();
        ip2.show();

        //SimpleMultiThreading.threadHaltUnClean();

        // important, we need to use the adjusted parameters here as well
        final CanvasMatchResult result = peakMatcher.deriveMatchResult(canvasPeaks1, canvasPeaks2);

        final List<PointMatch> inliers = result.getInlierPointMatchList();

        final ImagePlus ipnew1 = new ImagePlus("match_" + tileId1, ipm1.ip.duplicate());
        final ImagePlus ipnew2 = new ImagePlus("match_" + tileId2, ipm2.ip.duplicate());

        ImageDebugUtil.setPointMatchRois(inliers, ipnew1, ipnew2);

        if (inliers.size() > 3) {
            final List< Point > convexHull1 = CoverageUtils.computeConvexHull(ImageDebugUtil.getSourcePoints(inliers ) );
            final List< Point > convexHull2 = CoverageUtils.computeConvexHull(ImageDebugUtil.getTargetPoints(inliers ) );

        	final PolygonRoi r1 = CoverageUtils.createPolygon(convexHull1 );
        	final PolygonRoi r2 = CoverageUtils.createPolygon(convexHull2 );

        	final long sqArea1 = ipnew1.getWidth() * ipnew1.getHeight();
        	final long sqArea2 = ipnew2.getWidth() * ipnew2.getHeight();

        	final double area1 = CoverageUtils.calculatePolygonArea(convexHull1 ); //areaBruteForce( ipnew1.getProcessor(), r1 );
        	final double area2 = CoverageUtils.calculatePolygonArea(convexHull2 ); //areaBruteForce( ipnew2.getProcessor(), r2 );

        	final double relArea1 = area1/sqArea1;
        	final double relArea2 = area2/sqArea2;

        	final double[] c1 = CoverageUtils.calculateCenterOfMassBruteForce(r1 );
        	final double[] c2 = CoverageUtils.calculateCenterOfMassBruteForce(r2 );

        	LOG.debug( "Convex hull 1: " + convexHull1.size() + ", area=" + area1 + "/" + sqArea1 + " (" + Math.round( 100.0*relArea1 ) + "%)" );
        	LOG.debug( "center of mass 1: " + Util.printCoordinates( c1 ) );
        	LOG.debug( "" );
        	LOG.debug( "Convex hull 2: " + convexHull2.size() + ", area=" + area2 + "/" + sqArea2 + " (" + Math.round( 100.0*relArea2 ) + "%)" );
        	LOG.debug( "center of mass 2: " + Util.printCoordinates( c2 ) );

        	final Overlay o1 = new Overlay();
        	o1.add( r1 );
        	o1.add( new OvalRoi( c1[ 0 ] - 15, c1[ 1 ] - 15, 30, 30 ) );
        	ipnew1.setOverlay( o1 );

        	final Overlay o2 = new Overlay();
        	o2.add( r2 );
        	o2.add( new OvalRoi( c2[ 0 ] - 15, c2[ 1 ] - 15, 30, 30 ) );
        	ipnew2.setOverlay( o2 );
        } else {
            LOG.debug( "not enough points to derive convex hull" );
        }

        ipnew1.show();
        ipnew2.show();

        SimpleMultiThreading.threadHaltUnClean();

        // -------------------------------------------------------------------
        // display results ...
    }


    @SuppressWarnings("unused")
    static long areaBruteForce(final ImageProcessor r, final PolygonRoi roi )
    {
    	return areaBruteForce( new Rectangle( 0, 0, r.getWidth(), r.getHeight() ), roi );
    }

    private static long areaBruteForce( final Rectangle r, final PolygonRoi roi )
    {
    	long area = 0;

    	for ( int y = r.y; y < r.height + r.y; ++y )
    		for ( int x = r.x; x < r.width + r.x; ++x )
    			if ( roi.contains( x, y ) )
    				++area;

    	return area;
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

    private static final Logger LOG = LoggerFactory.getLogger(GeometricDescriptorMatcherTest.class);
}

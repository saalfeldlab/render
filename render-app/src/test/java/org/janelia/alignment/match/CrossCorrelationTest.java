package org.janelia.alignment.match;

import com.fasterxml.jackson.core.JsonProcessingException;

import ij.CompositeImage;
import ij.ImageJ;
import ij.ImagePlus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import mpicbg.imglib.multithreading.SimpleMultiThreading;
import mpicbg.models.InvertibleBoundable;
import mpicbg.models.PointMatch;
import mpicbg.stitching.StitchingParameters;
import mpicbg.stitching.fusion.OverlayFusion;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.type.numeric.real.FloatType;
import plugin.Stitching_Pairwise;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
import org.janelia.alignment.match.parameters.CrossCorrelationParameters;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageDebugUtil;
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
            { "Z0620_23m_VNC", "Sec25", "v2_acquire", "20-07-19_184758_0-0-1.10500.0", "20-07-19_184758_0-0-2.10500.0"},

            // test pair 6
            { "Z0620_23m_VNC", "Sec25", "v2_acquire", "20-07-19_230913_0-0-1.10772.0", "20-07-19_230913_0-0-2.10772.0"}
    };

    @Test
    public void testNothing() {
        Assert.assertTrue(true);
    }

    public static void main(final String[] args) {

       // -------------------------------------------------------------------
        // NOTES:
        //
        //   1. make sure /groups/flyem/data is mounted  ( from smb://dm11.hhmi.org/flyemdata )

        LOG.debug("starting cross correlation ...");

        // -------------------------------------------------------------------
        // setup test parameters ...

        // change this index (0 - 5) to work with a different tile pair
        final int testTilePairIndex = 0;

        final String owner = TEST_TILE_PAIRS[testTilePairIndex][0];
        final String project = TEST_TILE_PAIRS[testTilePairIndex][1];
        final String stack = TEST_TILE_PAIRS[testTilePairIndex][2];
        final String tileId1 = TEST_TILE_PAIRS[testTilePairIndex][3];
        final String tileId2 = TEST_TILE_PAIRS[testTilePairIndex][4];

        // smaller clipsize is closer to the actual overlap
        final Integer clipSize = 250;

        //setup cross correlation parameters
        final double renderScale = 0.4;

        // initial blurring (no!)
        // final double sigma = 1;

        final CrossCorrelationParameters crossCorrelationParameters = new CrossCorrelationParameters();
        crossCorrelationParameters.fullScaleSampleSize = 250;
        crossCorrelationParameters.fullScaleStepSize = 5;
        crossCorrelationParameters.minResultThreshold = 0.7; // SP suggests: maybe higher

        final MatchDerivationParameters matchDerivationParameters = getMatchFilterParameters();

        // -------------------------------------------------------------------
        // run test ...

        final RenderParameters renderParametersTile1 =
                GeometricDescriptorMatcherTest.getRenderParametersForTile(owner, project, stack, tileId1, renderScale, false, clipSize, MontageRelativePosition.LEFT);
        final RenderParameters renderParametersTile2 =
                GeometricDescriptorMatcherTest.getRenderParametersForTile(owner, project, stack, tileId2, renderScale, false, clipSize, MontageRelativePosition.RIGHT);

        // using cache helps a little with loading large masks over VPN
        final ImageProcessorCache imageProcessorCache =
                new ImageProcessorCache(4 * 15000 * 10000, // 4 big images
                                        true,
                                        false);
        final ImageProcessorWithMasks ipm1 = Renderer.renderImageProcessorWithMasks(renderParametersTile1,
                                                                                    imageProcessorCache);
        final ImageProcessorWithMasks ipm2 = Renderer.renderImageProcessorWithMasks(renderParametersTile2,
                                                                                    imageProcessorCache);

        final ImagePlus ip1 = new ImagePlus("tile_" + tileId1, ipm1.ip);
        final ImagePlus ip2 = new ImagePlus("tile_" + tileId2, ipm2.ip);

        new ImageJ();
        ip1.show();
        ip2.show();
        
        final CanvasCorrelationMatcher matcher =
                new CanvasCorrelationMatcher(crossCorrelationParameters,
                                             matchDerivationParameters,
                                             renderScale);
        final CanvasMatchResult result = matcher.deriveMatchResult(ip1, ipm1.mask,
                                                                   ip2, ipm2.mask,
                                                                   true);
        final List<PointMatch> inliers = result.getInlierPointMatchList();

//        // NOTE: stored matches are converted to full scale using this method:
//        final List<CanvasMatches> fullScaleMatches = result.getInlierMatchesList(pGroupId, pId,
//                                                                                 qGroupId, qId,
//                                                                                 renderScale,
//                                                                                 pOffsets, qOffsets);
        LOG.info( "ransac: " + result );
        try {
            final mpicbg.trakem2.transform.TranslationModel2D model = new mpicbg.trakem2.transform.TranslationModel2D();
			model.fit( inliers );
			LOG.info( model.toString() );

			final ArrayList<InvertibleBoundable> models = new ArrayList<>();
			models.add( new mpicbg.trakem2.transform.TranslationModel2D() );
			models.add( model.createInverse() );

			final ArrayList<ImagePlus> images = new ArrayList< ImagePlus >();
			images.add( ip1 );
			images.add( ip2 );

			final CompositeImage overlay = OverlayFusion.createOverlay( new FloatType(), images, models, 2, 1, new NLinearInterpolatorFactory<FloatType>() );
			overlay.show();

			//Stitching_Pairwise.fuse( new FloatType(), ip1, ip2, models, params );
            //showStitchedResult(renderParametersTile1, renderParametersTile2, imageProcessorCache, model);

        } catch (final Exception e) {
            LOG.info("ignoring error", e);
        }

        // visualize result
        ImageDebugUtil.setPointMatchRois( inliers, ip1, ip2 );

        SimpleMultiThreading.threadHaltUnClean();

    }

    public static void showStitchedResult(final RenderParameters renderParametersTile1,
                                          final RenderParameters renderParametersTile2,
                                          final ImageProcessorCache imageProcessorCache,
                                          final mpicbg.trakem2.transform.TranslationModel2D model)
            throws JsonProcessingException {

        final TileSpec tileSpec2 = renderParametersTile2.getTileSpecs().get(0);
        final LeafTransformSpec modelSpec = new LeafTransformSpec(model.getClass().getName(), model.toDataString());
        tileSpec2.addTransformSpecs(Collections.singletonList(modelSpec));
        tileSpec2.deriveBoundingBox(tileSpec2.getMeshCellSize(), true);

        final RenderParameters stitchedRenderParameters =
                RenderParameters.parseJson(renderParametersTile1.toJson());
        stitchedRenderParameters.addTileSpec(tileSpec2);
        stitchedRenderParameters.width += renderParametersTile2.width;
        stitchedRenderParameters.initializeDerivedValues();

        LOG.info(stitchedRenderParameters.toJson());

        final ImageProcessorWithMasks ipm3 = Renderer.renderImageProcessorWithMasks(stitchedRenderParameters,
                                                                                    imageProcessorCache);

        final ImagePlus ip3 = new ImagePlus("stitched_result", ipm3.ip);
        ip3.show();
    }

    static MatchDerivationParameters getMatchFilterParameters() {

        final MatchDerivationParameters matchFilterParameters = new MatchDerivationParameters();

        matchFilterParameters.matchModelType = ModelType.TRANSLATION;
        matchFilterParameters.matchIterations = 1000;
        matchFilterParameters.matchMaxEpsilonFullScale = 2.0f;
        matchFilterParameters.matchMinInlierRatio = 0.0f;
        matchFilterParameters.matchMinNumInliers = 20;
        matchFilterParameters.matchMaxTrust = 3.0;
        matchFilterParameters.matchFilter = MatchFilter.FilterType.SINGLE_SET;

        return matchFilterParameters;
    }

    private static final Logger LOG = LoggerFactory.getLogger(CrossCorrelationTest.class);
}

package org.janelia.alignment.match;

import ij.ImagePlus;

import mpicbg.imglib.multithreading.SimpleMultiThreading;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
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

    public static void main(final String[] args) {

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

        ip1.show();
        ip2.show();

        SimpleMultiThreading.threadHaltUnClean();

    }

    private static final Logger LOG = LoggerFactory.getLogger(CrossCorrelationTest.class);
}

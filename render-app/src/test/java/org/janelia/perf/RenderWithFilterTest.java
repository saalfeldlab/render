package org.janelia.perf;

import ij.process.ImageProcessor;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
//import org.janelia.alignment.filter.FilterSpec;
//import org.janelia.alignment.filter.LinearIntensityMap8BitFilter2;
//import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
//import org.junit.Assert;

/**
 * Render a tile with and without intensity correction filter to profile process ...
 *
 * @author Eric Trautman
 */
public class RenderWithFilterTest {

    public static void main(final String[] args) {

        final String baseDataUrl = "http://renderer-dev.int.janelia.org:8080/render-ws/v1";
        final String owner = "cellmap";
        final String project = "jrc_mus_thymus_1";
//        final String stack = "v2_acquire_align";
        final String stack = "v2_acquire_align_ic";

        final String tileId = "23-06-05_201000_0-0-1.1275.0";

        final String tileUrl = String.format("%s/owner/%s/project/%s/stack/%s/tile/%s/render-parameters",
                                             baseDataUrl, owner, project, stack, tileId);

        final RenderParameters renderParameters = RenderParameters.loadFromUrl(tileUrl);

        final ImageProcessor oldIp = renderOld(renderParameters);
//        final ImageProcessor newIp = renderNew(renderParameters);
//        final int pixelCount = oldIp.getPixelCount();
//
//        for (int i = 0; i < pixelCount; i++) {
//            final int oldIntensity = oldIp.get(i);
//            final int newIntensity = newIp.get(i);
//            Assert.assertEquals("intensities don't match for index " + i, oldIntensity, newIntensity);
//        }
    }

    private static ImageProcessor renderOld(final RenderParameters renderParameters) {
        final TransformMeshMappingWithMasks.ImageProcessorWithMasks ipwm =
                Renderer.renderImageProcessorWithMasks(renderParameters,
                                                       ImageProcessorCache.DISABLED_CACHE,
                                                       null);
//        final BufferedImage bufferedImage = ipwm.ip.getBufferedImage();
//        System.out.println(bufferedImage);
        return ipwm.ip;
    }

//    private static ImageProcessor renderNew(final RenderParameters renderParameters) {
//        final TileSpec tileSpec = renderParameters.getTileSpecs().get(0);
//        tileSpec.setFilterSpec(new FilterSpec(LinearIntensityMap8BitFilter2.class.getName(),
//                                              tileSpec.getFilterSpec().getParameters()));
//        final TransformMeshMappingWithMasks.ImageProcessorWithMasks ipwm =
//                Renderer.renderImageProcessorWithMasks(renderParameters,
//                                                       ImageProcessorCache.DISABLED_CACHE,
//                                                       null);
////        final BufferedImage bufferedImage = ipwm.ip.getBufferedImage();
////        System.out.println(bufferedImage);
//        return ipwm.ip;
//    }
}

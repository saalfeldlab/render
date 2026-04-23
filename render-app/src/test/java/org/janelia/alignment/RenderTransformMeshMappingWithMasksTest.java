package org.janelia.alignment;

import ij.process.ImageProcessor;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;

import org.janelia.alignment.util.ImageProcessorCache;
import org.junit.Assert;
import org.junit.Test;

import static org.janelia.alignment.Renderer.renderImageProcessorWithMasks;

/**
 * Tests the {@link RenderTransformMeshMappingWithMasks} class.
 */
public class RenderTransformMeshMappingWithMasksTest {

    @Test
    public void testMap() {
        // TODO: uncomment below to run test
        // renderProblemArea();

        // one way to "solve" problem is to revert RenderTransformMeshMappingWithMasks to commit just before
        // Tobias' December 2024 / January 2025 changes: 25bcdd4cecac5baace30997d950793c82a65e832
    }

    @SuppressWarnings("unused")
    private static void renderProblemArea() {

        // notes:
        //   a) w61_s109_z12_problem_a.json has the tile order flipped in descending tileId order
        //      because I noticed a black line when I was working on something else.
        //      I don't think the descending order has anything to do with the source problem,
        //      other than where it shows up.
        //   b) repo path is render-app/src/test/resources/stitch-test/w61_s109_z12_problem_a.json

        final RenderParameters renderParameters =
                RenderParameters.loadFromUrl("src/test/resources/stitch-test/w61_s109_z12_problem_a.json");

        final TransformMeshMappingWithMasks.ImageProcessorWithMasks worldTarget =
                renderImageProcessorWithMasks(renderParameters, ImageProcessorCache.DISABLED_CACHE);
        final ImageProcessor ip = worldTarget.ip;

        boolean foundZeroPixel = false;
        for (int y = 0; y < ip.getHeight(); y++) {
            for (int x = 0; x < ip.getWidth(); x++) {
                final int pixel = ip.getPixel(x, y);
                if (pixel == 0) {
                    foundZeroPixel = true;
                }
                System.out.printf("%4d", pixel);
            }
            System.out.println();
        }

        if (foundZeroPixel) {
            // TODO: uncomment below and break on Assert.fail call to see pixels in IDE
            // final BufferedImage bi = ip.getBufferedImage();
            Assert.fail("found zero pixel in rendered image");
        }

    }
}

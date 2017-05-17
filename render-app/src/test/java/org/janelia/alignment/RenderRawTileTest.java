package org.janelia.alignment;

import ij.process.ImageProcessor;

import java.awt.image.BufferedImage;

import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests rendering of a raw tile without transformations.
 *
 * @author Eric Trautman
 */
public class RenderRawTileTest {

    @Test
    public void testRender() throws Exception {

        final ImageAndMask imageWithoutMask = new ImageAndMask("src/test/resources/raw-tile-test/raw-tile.png", null);
        final ImageProcessorCache imageProcessorCache = new ImageProcessorCache();

        final ImageProcessor rawIp = imageProcessorCache.get(imageWithoutMask.getImageUrl(), 0, false);
        final BufferedImage rawImage = rawIp.getBufferedImage();

        final TileSpec tileSpec = new TileSpec();
        tileSpec.putMipmap(0, imageWithoutMask);

        final RenderParameters tileRenderParameters =
                new RenderParameters(null, 0, 0, rawIp.getWidth(), rawIp.getHeight(), 1.0);

        tileRenderParameters.addTileSpec(tileSpec);
        tileRenderParameters.setSkipInterpolation(true);

        final BufferedImage renderedImage = tileRenderParameters.openTargetImage();

        Render.render(tileRenderParameters,
                      renderedImage,
                      imageProcessorCache);

        Assert.assertEquals("bad rendered image width",
                            rawImage.getWidth(), renderedImage.getWidth());

        Assert.assertEquals("bad rendered image height",
                            rawImage.getWidth(), renderedImage.getHeight());

        for (int x = 0; x < rawImage.getWidth(); x++) {
            for (int y = 0; y < rawImage.getHeight(); y++) {
                Assert.assertEquals("bad rendered pixel at (" + x + ", " + y + ")",
                                    rawImage.getRGB(x, y), renderedImage.getRGB(x, y));
            }
        }
    }
}

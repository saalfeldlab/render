package org.janelia.alignment;

import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import java.awt.image.BufferedImage;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;

import org.janelia.alignment.loader.ImageJDefaultLoader;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests rendering of a raw tile without transformations.
 *
 * @author Eric Trautman
 */
public class RenderRawTileTest {

    @Test
    public void testRender() {

        final ImageAndMask imageWithoutMask = new ImageAndMask("src/test/resources/raw-tile-test/raw-tile.png",
                                                               null);

        final ImageProcessor rawIp = ImageJDefaultLoader.INSTANCE.load(imageWithoutMask.getImageUrl());
        final BufferedImage rawImage =
                ArgbRenderer.targetToARGBImage(new TransformMeshMappingWithMasks.ImageProcessorWithMasks(rawIp,
                                                                                                         null,
                                                                                                         null),
                                               false);

        final ChannelSpec channelSpec = new ChannelSpec();
        channelSpec.putMipmap(0, imageWithoutMask);

        final TileSpec tileSpec = new TileSpec();
        tileSpec.addChannel(channelSpec);

        final RenderParameters tileRenderParameters =
                new RenderParameters(null, 0, 0, rawIp.getWidth(), rawIp.getHeight(), 1.0);

        tileRenderParameters.addTileSpec(tileSpec);
        tileRenderParameters.setSkipInterpolation(true);
        tileRenderParameters.initializeDerivedValues();

        final BufferedImage renderedImage = tileRenderParameters.openTargetImage();

        ArgbRenderer.render(tileRenderParameters,
                            renderedImage,
                            ImageProcessorCache.DISABLED_CACHE);

        assertEquals(rawImage.getWidth(), renderedImage.getWidth(),
                     "bad rendered image width");

        assertEquals(rawImage.getHeight(), renderedImage.getHeight(),
                     "bad rendered image height");

        for (int x = 0; x < rawImage.getWidth(); x++) {
            for (int y = 0; y < rawImage.getHeight(); y++) {
                assertEquals(rawImage.getRGB(x, y), renderedImage.getRGB(x, y),
                             "bad rendered pixel at (" + x + ", " + y + ")");
            }
        }
    }
}

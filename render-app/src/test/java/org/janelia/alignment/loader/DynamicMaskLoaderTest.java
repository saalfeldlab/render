package org.janelia.alignment.loader;

import ij.process.ImageProcessor;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link DynamicMaskLoader} class.
 *
 * @author Eric Trautman
 */
public class DynamicMaskLoaderTest {

    @Test
    public void testLoad() {

        int width = 56;
        int height = 24;
        String maskUrl = "mask://outside-box?minX=10&minY=0&maxX=56&maxY=23&width=" + width + "&height=" + height;

        ImageProcessor maskProcessor = DynamicMaskLoader.INSTANCE.load(maskUrl);

        Assert.assertEquals("invalid mask width for level 0",
                            width, maskProcessor.getWidth());
        Assert.assertEquals("invalid mask height for level 0",
                            height, maskProcessor.getHeight());

        width = width / 2;
        height = height / 2;
        maskUrl = maskUrl + "&level=1";
        maskProcessor = DynamicMaskLoader.INSTANCE.load(maskUrl);

        Assert.assertEquals("invalid mask width for level 1",
                            width, maskProcessor.getWidth());
        Assert.assertEquals("invalid mask height for level 1",
                            height, maskProcessor.getHeight());
    }

}

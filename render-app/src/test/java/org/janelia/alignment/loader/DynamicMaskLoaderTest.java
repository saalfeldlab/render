package org.janelia.alignment.loader;

import ij.process.ImageProcessor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

        assertEquals(width, maskProcessor.getWidth(),
                     "invalid mask width for level 0");
        assertEquals(height, maskProcessor.getHeight(),
                     "invalid mask height for level 0");

        width = width / 2;
        height = height / 2;
        maskUrl = maskUrl + "&level=1";
        maskProcessor = DynamicMaskLoader.INSTANCE.load(maskUrl);

        assertEquals(width, maskProcessor.getWidth(),
                     "invalid mask width for level 1");
        assertEquals(height, maskProcessor.getHeight(),
                     "invalid mask height for level 1");
    }

}

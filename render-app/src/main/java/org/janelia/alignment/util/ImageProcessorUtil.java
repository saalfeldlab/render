package org.janelia.alignment.util;

import ij.process.ImageProcessor;

/**
 * Utility methods for working with images.
 *
 * @author Stephan Preibisch
 */
public class ImageProcessorUtil {

    /**
     * @return true if the specified pixel or any immediately adjacent pixel is found in the mask; otherwise false.
     */
    public static boolean isMasked(final ImageProcessor maskProcessor,
                                   final int x,
                                   final int y) {
        return maskProcessor.get(x, y) == 0 ||
               maskProcessor.get(x + 1, y) == 0 || maskProcessor.get(x - 1, y) == 0 ||
               maskProcessor.get(x, y + 1) == 0 || maskProcessor.get(x, y - 1) == 0;
    }

}

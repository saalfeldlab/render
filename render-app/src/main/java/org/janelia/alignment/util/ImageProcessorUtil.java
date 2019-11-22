package org.janelia.alignment.util;

import ij.process.ImageProcessor;

/**
 * Utility methods for working with images.
 *
 * @author Stephan Preibisch
 */
public class ImageProcessorUtil {

    /*** @return true if the specified pixel is masked out; otherwise false.
     */
    public static boolean isMaskedOut(final int x,
                                      final int y,
                                      final ImageProcessor maskProcessor) {
        return maskProcessor.get(x, y) == 0;
    }

    /**
     * @return true if the specified pixel or one near it is masked out; otherwise false.
     */
    public static boolean isNearMaskedOut(final int x,
                                          final int y,
                                          final int distance,
                                          final ImageProcessor maskProcessor) {
        boolean isNearMask = false;

        final int minX = Math.max(0, x - distance);
        final int maxX = Math.min(maskProcessor.getWidth(), x + distance + 1);
        final int minY = Math.max(0, y - distance);
        final int maxY = Math.min(maskProcessor.getHeight(), y + distance + 1);

        for (int nearbyX = minX; nearbyX < maxX; nearbyX++) {
            for (int nearbyY = minY; nearbyY < maxY; nearbyY++) {
                if (isMaskedOut(nearbyX, nearbyY, maskProcessor)) {
                    isNearMask = true;
                    break;
                }
            }
        }
        return isNearMask;
    }

}

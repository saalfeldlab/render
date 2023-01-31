package org.janelia.alignment.loader;

import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import mpicbg.trakem2.util.Downsampler;

import static org.janelia.alignment.spec.stack.MipmapPathBuilder.DYNAMIC_MASK_PROTOCOL;

/**
 * Loads a dynamically created mask whose attributes are specified by a (hack) URI.
 *
 * @author Eric Trautman
 */
public class DynamicMaskLoader
        implements ImageLoader {

    /** Shareable instance of this loader. */
    public static final DynamicMaskLoader INSTANCE = new DynamicMaskLoader();

    // mask://outside-box?minX=10&minY=0&maxX=56&maxY=23&width=56&height=24
    private static final Pattern OUTSIDE_BOX_URL_PATTERN =
            Pattern.compile(DYNAMIC_MASK_PROTOCOL +
                            "outside-box\\?minX=(\\d+)&minY=(\\d+)&maxX=(\\d+)&maxY=(\\d+)&width=(\\d+)&height=(\\d+).*");

    private static final Pattern LEVEL_PATTERN = Pattern.compile(".*&level=(\\d+).*");

    @Override
    public boolean hasSame3DContext(final ImageLoader otherLoader) {
        return true;
    }

    @Override
    public ImageProcessor load(final String urlString)
            throws IllegalArgumentException {

        ByteProcessor maskProcessor;

        final DynamicMaskDescription description = parseUrl(urlString);
        final int unmaskedWidth = description.maxX - description.minX;
        final int unmaskedHeight = description.maxY - description.minY;

        maskProcessor = new ByteProcessor(description.width, description.height);

        maskProcessor.setColor(255);
        maskProcessor.fillRect(description.minX, description.minY, unmaskedWidth, unmaskedHeight);

        final Matcher levelMatcher = LEVEL_PATTERN.matcher(urlString);
        if (levelMatcher.matches() && levelMatcher.groupCount() == 1) {
            final int desiredLevel = Integer.parseInt(levelMatcher.group(1));
            if (desiredLevel > 0) {
                maskProcessor = Downsampler.downsampleByteProcessor(maskProcessor, desiredLevel);
            }
        }

        return maskProcessor;
    }

    public static class DynamicMaskDescription {
        public final int minX;
        public final int minY;
        public final int maxX;
        public final int maxY;
        public final int width;
        public final int height;

        public DynamicMaskDescription(final int minX,
                                      final int minY,
                                      final int maxX,
                                      final int maxY,
                                      final int width,
                                      final int height) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            this.width = width;
            this.height = height;
        }

        @Override
        public String toString() {
            return "mask://outside-box?minX=" + minX + "&minY=" + minY + "&maxX=" + maxX + "&maxY=" + maxY +
                   "&width=" + width + "&height=" + height;
        }

        public DynamicMaskDescription withWidthAndHeight(final int changedWidth,
                                                         final int changedHeight) {
            return new DynamicMaskDescription(minX, minY,
                                              Math.min(maxX, changedWidth), Math.min(maxY, changedHeight),
                                              changedWidth, changedHeight);
        }
    }

    public static DynamicMaskDescription parseUrl(final String urlString) throws IllegalArgumentException {
        final Matcher m = OUTSIDE_BOX_URL_PATTERN.matcher(urlString);
        if (m.matches() && m.groupCount() == 6) {
            return new DynamicMaskDescription(Integer.parseInt(m.group(1)),
                                              Integer.parseInt(m.group(2)),
                                              Integer.parseInt(m.group(3)),
                                              Integer.parseInt(m.group(4)),
                                              Integer.parseInt(m.group(5)),
                                              Integer.parseInt(m.group(6)));
        } else {
            throw new IllegalArgumentException("failed to parse dynamic mask URL: " + urlString);
        }
    }
}

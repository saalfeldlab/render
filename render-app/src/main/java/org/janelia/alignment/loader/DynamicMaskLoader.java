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

        final Matcher m = OUTSIDE_BOX_URL_PATTERN.matcher(urlString);
        if (m.matches() && m.groupCount() == 6) {

            try {
                final int unmaskedX = Integer.parseInt(m.group(1));
                final int unmaskedY = Integer.parseInt(m.group(2));
                final int unmaskedWidth = Integer.parseInt(m.group(3)) - unmaskedX;
                final int unmaskedHeight = Integer.parseInt(m.group(4)) - unmaskedY;
                final int fullWidth = Integer.parseInt(m.group(5));
                final int fullHeight = Integer.parseInt(m.group(6));

                maskProcessor = new ByteProcessor(fullWidth, fullHeight);

                maskProcessor.setColor(255);
                maskProcessor.fillRect(unmaskedX, unmaskedY, unmaskedWidth, unmaskedHeight);

                final Matcher levelMatcher = LEVEL_PATTERN.matcher(urlString);
                if (levelMatcher.matches() && levelMatcher.groupCount() == 1) {
                    final int desiredLevel = Integer.parseInt(levelMatcher.group(1));
                    if (desiredLevel > 0) {
                        maskProcessor = Downsampler.downsampleByteProcessor(maskProcessor, desiredLevel);
                    }
                }

            } catch (final NumberFormatException e) {
                throw new IllegalArgumentException("failed to build dynamic mask for: " + urlString, e);
            }

        } else {
            throw new IllegalArgumentException("failed to parse dynamic mask URL: " + urlString);
        }

        return maskProcessor;
    }
}

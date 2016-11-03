package org.janelia.alignment;

import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An encapsulated list of source and target {@link ImageProcessorWithMasks} instances
 * mapped to each other by channel name that supports pixel mapping using a
 * common (pre-calculated) transformation (see @link{#mapPixel}).
 *
 * @author Eric Trautman
 */
public class ChannelPairs {

    private final List<ImageProcessorWithMasks> sourceList;
    private final List<ImageProcessorWithMasks> targetList;
    private final boolean isInterpolated;
    private final int targetWidth;
    private final int targetHeight;

    /**
     * Single channel constructor.
     *
     * @param  channelName      name of the single channel.
     *
     * @param  source           source channel processor (with masks).
     *
     * @param  target           target channel processor (with masks).
     *
     * @param  isInterpolated   if true, use {@link ImageProcessor#BILINEAR} interpolation to derive pixel
     *                          intensities from source channels; otherwise don't interpolate.
     */
    public ChannelPairs(final String channelName,
                        final ImageProcessorWithMasks source,
                        final ImageProcessorWithMasks target,
                        final boolean isInterpolated) {
        this(Collections.singletonMap(channelName, source),
             Collections.singletonMap(channelName, target),
             isInterpolated);
    }

    /**
     * Multi-channel constructor.
     *
     * @param  sourceChannels   map of channel names to source channel processors.
     *
     * @param  targetChannels   map of channel names to target channel processors.
     *
     * @param  isInterpolated   if true, use {@link ImageProcessor#BILINEAR} interpolation to derive pixel
     *                          intensities from source channels; otherwise don't interpolate.
     *
     * @throws IllegalArgumentException
     *   if none of the source channels map to a target channel or
     *   if mapped target channels have different dimensions.
     */
    public ChannelPairs(final Map<String, ImageProcessorWithMasks> sourceChannels,
                        final Map<String, ImageProcessorWithMasks> targetChannels,
                        final boolean isInterpolated)
            throws IllegalArgumentException {

        this.sourceList = new ArrayList<>(sourceChannels.size());
        this.targetList = new ArrayList<>(sourceChannels.size());
        this.isInterpolated = isInterpolated;

        Integer commonTargetWidth = null;
        int commonTargetHeight = -1;

        ImageProcessorWithMasks sourceChannel;
        ImageProcessorWithMasks targetChannel;
        for (final String channelId : sourceChannels.keySet()) {
            sourceChannel = sourceChannels.get(channelId);
            targetChannel = targetChannels.get(channelId);
            if (targetChannel == null) {
                LOG.warn("skipping channel '{}' because it is missing from target", channelId);
            } else {

                if (commonTargetWidth == null) {
                    commonTargetWidth = targetChannel.getWidth();
                    commonTargetHeight = targetChannel.getHeight();
                }

                if ((commonTargetWidth == targetChannel.getWidth()) &&
                    (commonTargetHeight == targetChannel.getHeight())){

                    if (isInterpolated) {
                        sourceChannel.ip.setInterpolationMethod(ImageProcessor.BILINEAR);
                        if (sourceChannel.mask != null) {
                            sourceChannel.mask.setInterpolationMethod(ImageProcessor.BILINEAR);
                        }
                    }

                    targetChannel.outside = new ByteProcessor(commonTargetWidth, commonTargetHeight);

                    sourceList.add(sourceChannel);
                    targetList.add(targetChannel);

                } else {
                    throw new IllegalArgumentException(
                            "All target channels must have the same dimensions.  Channel '" + channelId +
                            "' is " + targetChannel.getWidth() + "x" + targetChannel.getHeight() +
                            " but other channel(s) are " + commonTargetWidth + "x" + commonTargetHeight);
                }

            }
        }

        if (commonTargetWidth == null) {
            throw new IllegalArgumentException("None of the source channels (" + sourceChannels.keySet() +
                                               ") map to target channels (" + targetChannels.keySet() + ").");
        }

        this.targetWidth = commonTargetWidth;
        this.targetHeight = commonTargetHeight;
    }

    /**
     * @return the common width for all mapped target channels.
     */
    public int getTargetWidth() {
        return targetWidth;
    }

    /**
     * @return the common height for all mapped target channels.
     */
    public int getTargetHeight() {
        return targetHeight;
    }

    /**
     * Sets the intensity of the pixel at (targetX, targetY) to the intensity of the pixel at (sourceX, sourceY)
     * for all mapped channels.
     *
     * @param  sourceX  source x coordinate.
     * @param  sourceY  source y coordinate.
     * @param  targetX  target x coordinate.
     * @param  targetY  target y coordinate.
     */
    public void mapPixel(final double sourceX,
                         final double sourceY,
                         final int targetX,
                         final int targetY) {

        for (int index = 0; index < sourceList.size(); index++) {

            final ImageProcessorWithMasks source = sourceList.get(index);
            final ImageProcessorWithMasks target = targetList.get(index);

            if (isInterpolated) {

                target.ip.set(targetX, targetY,
                              source.ip.getPixelInterpolated(sourceX, sourceY));

                // TODO: find out if short alpha channels need to be supported

                // final int is = source.ip.getPixelInterpolated(t[0], t[1]);
                // final int it = target.ip.get(x, y);
                // final double f = alpha.getPixelInterpolated(t[0], t[1]) / 255.0;
                // final double v = it + f * (is - it);
                // target.ip.set(x, y, (int) Math.max(0, Math.min(65535, Math.round(v))));

                if (source.mask != null) {
                    target.mask.set(targetX, targetY,
                                    source.mask.getPixelInterpolated(sourceX, sourceY));
                }

            } else {

                target.ip.set(targetX, targetY,
                              source.ip.getPixel((int) (sourceX + 0.5f), (int) (sourceY + 0.5f)));

                if (source.mask != null) {
                    target.mask.set(targetX, targetY,
                                    source.mask.getPixel((int) (sourceX + 0.5f), (int) (sourceY + 0.5f)));
                }

            }

            target.outside.set(targetX, targetY, 0xff);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(ChannelPairs.class);
}
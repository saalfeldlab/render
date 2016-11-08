package org.janelia.alignment;

import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;
import mpicbg.util.Timer;

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
    private final int targetOffsetX;
    private final int targetOffsetY;
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
                        final int targetOffsetX,
                        final int targetOffsetY,
                        final boolean isInterpolated) {
        this(Collections.singletonMap(channelName, source),
             Collections.singletonMap(channelName, target),
             targetOffsetX,
             targetOffsetY,
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
                        final int targetOffsetX,
                        final int targetOffsetY,
                        final boolean isInterpolated)
            throws IllegalArgumentException {

        this.sourceList = new ArrayList<>(sourceChannels.size());
        this.targetList = new ArrayList<>(sourceChannels.size());
        this.isInterpolated = isInterpolated;
        this.targetOffsetX = targetOffsetX;
        this.targetOffsetY = targetOffsetY;

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

        }
    }

    public static ImageProcessor blendChannels(final List<ChannelPairs> channelPairsList,
                                               final int targetWidth,
                                               final int targetHeight,
                                               final boolean isBinaryMask) {

        final Timer timer = new Timer();
        timer.start();

        final ByteProcessor worldTarget = new ByteProcessor(targetWidth, targetHeight);

        double sourceAlpha;

        for (final ChannelPairs channelPairs : channelPairsList) {

            final int w = channelPairs.getTargetWidth();
            final int h = channelPairs.getTargetHeight();

            for (int y = 0; y <= h; ++y) {
                for (int x = 0; x <= w; ++x) {

                    final int worldTargetX = channelPairs.targetOffsetX + x;
                    final int worldTargetY = channelPairs.targetOffsetY + y;

                    for (final ImageProcessorWithMasks localTarget : channelPairs.targetList) {

                        if (localTarget.mask == null) {
                            sourceAlpha = 1.0;
                        } else {
                            sourceAlpha = localTarget.mask.getPixel(x, y) / 255.0; // TODO: do we need to divide by mask maskIntensity instead?
                            if (isBinaryMask && (sourceAlpha > 0.0)) {
                                sourceAlpha = 1.0;
                            }
                        }

                        final int sourceIntensity = localTarget.ip.getPixel(x, y);
                        final int targetIntensity = worldTarget.get(worldTargetX, worldTargetY);
                        final double targetAlpha = 1.0; // TODO: verify this is right
                        final int blendedIntensity = getBlendedIntensity(sourceIntensity, sourceAlpha,
                                                                         targetIntensity, targetAlpha);

                        worldTarget.set(worldTargetX, worldTargetY, blendedIntensity);
                    }

                }
            }

        }

        LOG.info("blendChannels: {} channel pairs took {} milliseconds to blend",
                 channelPairsList.size(), timer.stop());

        return worldTarget;
    }

    public static int getBlendedIntensity(final int sourceIntensity,
                                          final double sourceAlpha,
                                          final int targetIntensity,
                                          final double targetAlpha) {

        final int blendedIntensity;

        if (targetIntensity == 0) {

            blendedIntensity = (int) ((sourceIntensity * sourceAlpha) + 0.5);

        } else {

            final double blendedAlpha = sourceAlpha + (targetAlpha * (1 - sourceAlpha));

            if (blendedAlpha == 0) {
                blendedIntensity = 0;
            } else {
                blendedIntensity = (int) (
                        (((sourceIntensity * sourceAlpha) + (targetIntensity * targetAlpha * (1 - sourceAlpha))) /
                         blendedAlpha)
                        + 0.5);
            }
        }

        return blendedIntensity;
    }

    private static final Logger LOG = LoggerFactory.getLogger(ChannelPairs.class);

}
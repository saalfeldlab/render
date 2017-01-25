package org.janelia.alignment.mapper;

import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.ChannelMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

/**
 * TODO: add javadoc
 *
 * @author Eric Trautman
 */
public class MultiChannelMapper
        implements PixelMapper {

    protected final List<ImageProcessorWithMasks> normalizedSourceList;
    protected final List<ImageProcessorWithMasks> targetList;
    protected final boolean isMappingInterpolated;
    protected final int targetWidth;
    protected final int targetHeight;

    public MultiChannelMapper(final ChannelMap sourceChannels,
                              final ChannelMap targetChannels,
                              final boolean isMappingInterpolated) {

        this.isMappingInterpolated = isMappingInterpolated;

        this.normalizedSourceList = new ArrayList<>(sourceChannels.size());
        this.targetList = new ArrayList<>(sourceChannels.size());

        Integer commonTargetWidth = null;
        int commonTargetHeight = -1;

        ImageProcessorWithMasks normalizedSource;
        ImageProcessorWithMasks targetChannel;
        for (final String channelName : sourceChannels.names()) {
            targetChannel = targetChannels.get(channelName);
            if (targetChannel == null) {
                LOG.warn("skipping channel '{}' because it is missing from target", channelName);
            } else {

                normalizedSource = SingleChannelMapper.normalizeSourceForTarget(sourceChannels.get(channelName),
                                                                                targetChannel.ip);

                if (commonTargetWidth == null) {
                    commonTargetWidth = targetChannel.getWidth();
                    commonTargetHeight = targetChannel.getHeight();
                }

                if ((commonTargetWidth == targetChannel.getWidth()) &&
                    (commonTargetHeight == targetChannel.getHeight())){

                    if (isMappingInterpolated) {
                        normalizedSource.ip.setInterpolationMethod(ImageProcessor.BILINEAR);
                    }

                    normalizedSourceList.add(normalizedSource);
                    targetList.add(targetChannel);

                } else {
                    throw new IllegalArgumentException(
                            "All target channels must have the same dimensions.  Channel '" + channelName +
                            "' is " + targetChannel.getWidth() + "x" + targetChannel.getHeight() +
                            " but other channel(s) are " + commonTargetWidth + "x" + commonTargetHeight);
                }

            }
        }

        if (commonTargetWidth == null) {
            throw new IllegalArgumentException("None of the source channels (" + sourceChannels +
                                               ") map to target channels (" + targetChannels + ").");
        }

        this.targetWidth = commonTargetWidth;
        this.targetHeight = commonTargetHeight;
    }

    @Override
    public int getTargetWidth() {
        return targetWidth;
    }

    @Override
    public int getTargetHeight() {
        return targetHeight;
    }

    @Override
    public boolean isMappingInterpolated() {
        return isMappingInterpolated;
    }

    @Override
    public void map(final double sourceX,
                    final double sourceY,
                    final int targetX,
                    final int targetY) {

        final int roundedSourceX = (int) Math.round(sourceX);
        final int roundedSourceY = (int) Math.round(sourceY);

        ImageProcessorWithMasks normalizedSource;
        ImageProcessorWithMasks target;
        for (int i = 0; i < normalizedSourceList.size(); i++) {
            normalizedSource = normalizedSourceList.get(i);
            target = targetList.get(i);
            target.ip.setf(targetX, targetY, normalizedSource.ip.getf(roundedSourceX, roundedSourceY));
        }
    }

    @Override
    public void mapInterpolated(final double sourceX,
                                final double sourceY,
                                final int targetX,
                                final int targetY) {

        ImageProcessorWithMasks normalizedSource;
        ImageProcessorWithMasks target;
        for (int i = 0; i < normalizedSourceList.size(); i++) {
            normalizedSource = normalizedSourceList.get(i);
            target = targetList.get(i);
            target.ip.setf(targetX, targetY, (float) normalizedSource.ip.getInterpolatedPixel(sourceX, sourceY));
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(MultiChannelMapper.class);

}

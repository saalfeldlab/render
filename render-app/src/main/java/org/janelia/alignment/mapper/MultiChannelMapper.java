package org.janelia.alignment.mapper;

import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    protected final List<ImageProcessor> targetList;
    protected final int targetOffsetX;
    protected final int targetOffsetY;
    protected final boolean isMappingInterpolated;
    protected final int targetWidth;
    protected final int targetHeight;

    public MultiChannelMapper(final Map<String, ImageProcessorWithMasks> sourceChannelMap,
                              final Map<String, ImageProcessor> targetChannelMap,
                              final int targetOffsetX,
                              final int targetOffsetY,
                              final boolean isMappingInterpolated) {

        this.targetOffsetX = targetOffsetX;
        this.targetOffsetY = targetOffsetY;
        this.isMappingInterpolated = isMappingInterpolated;

        this.normalizedSourceList = new ArrayList<>(sourceChannelMap.size());
        this.targetList = new ArrayList<>(sourceChannelMap.size());

        Integer commonTargetWidth = null;
        int commonTargetHeight = -1;

        ImageProcessorWithMasks normalizedSource;
        ImageProcessor targetChannel;
        for (final String channelId : sourceChannelMap.keySet()) {
            targetChannel = targetChannelMap.get(channelId);
            if (targetChannel == null) {
                LOG.warn("skipping channel '{}' because it is missing from target", channelId);
            } else {

                normalizedSource = SingleChannelMapper.normalizeSourceForTarget(sourceChannelMap.get(channelId),
                                                                                targetChannel);

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
                            "All target channels must have the same dimensions.  Channel '" + channelId +
                            "' is " + targetChannel.getWidth() + "x" + targetChannel.getHeight() +
                            " but other channel(s) are " + commonTargetWidth + "x" + commonTargetHeight);
                }

            }
        }

        if (commonTargetWidth == null) {
            throw new IllegalArgumentException("None of the source channels (" + sourceChannelMap.keySet() +
                                               ") map to target channels (" + targetChannelMap.keySet() + ").");
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

        final int roundedSourceX = (int) (sourceX + 0.5f);
        final int roundedSourceY = (int) (sourceY + 0.5f);
        final int worldTargetX = targetOffsetX + targetX;
        final int worldTargetY = targetOffsetY + targetY;

        ImageProcessorWithMasks normalizedSource;
        ImageProcessor target;
        for (int i = 0; i < normalizedSourceList.size(); i++) {
            normalizedSource = normalizedSourceList.get(i);
            target = targetList.get(i);
            target.set(worldTargetX, worldTargetY, normalizedSource.ip.getPixel(roundedSourceX, roundedSourceY));
        }
    }

    @Override
    public void mapInterpolated(final double sourceX,
                                final double sourceY,
                                final int targetX,
                                final int targetY) {

        final int worldTargetX = targetOffsetX + targetX;
        final int worldTargetY = targetOffsetY + targetY;

        ImageProcessorWithMasks normalizedSource;
        ImageProcessor target;
        for (int i = 0; i < normalizedSourceList.size(); i++) {
            normalizedSource = normalizedSourceList.get(i);
            target = targetList.get(i);
            target.set(worldTargetX, worldTargetY, normalizedSource.ip.getPixelInterpolated(sourceX, sourceY));
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(MultiChannelMapper.class);

}

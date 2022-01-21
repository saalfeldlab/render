package org.janelia.alignment.mapper;

import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.ChannelMap;

import static mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

/**
 * Maps source and mask pixels from a multi-channel source to a target canvas and mask.
 * Source regions that overlap with other already mapped sources are blended into the target.
 */
public class MultiChannelWithAlphaMapper
        extends MultiChannelMapper {

    protected final List<Double> sourceMaxMaskIntensityList;
    protected final List<Double> targetMaxMaskIntensityList;

    public MultiChannelWithAlphaMapper(final ChannelMap sourceChannels,
                                       final ChannelMap targetChannels,
                                       final boolean isMappingInterpolated) {

        super(sourceChannels, targetChannels, isMappingInterpolated);

        this.sourceMaxMaskIntensityList = new ArrayList<>(normalizedSourceList.size());
        for (final ImageProcessorWithMasks normalizedSource : normalizedSourceList) {
            this.sourceMaxMaskIntensityList.add(normalizedSource.mask.getMax());
        }

        this.targetMaxMaskIntensityList = new ArrayList<>(targetList.size());
        for (final ImageProcessorWithMasks target : targetList) {
            this.targetMaxMaskIntensityList.add(target.mask.getMax());
        }

        if (isMappingInterpolated) {
            for (final ImageProcessorWithMasks normalizedSource : normalizedSourceList) {
                normalizedSource.mask.setInterpolationMethod(ImageProcessor.BILINEAR);
            }
        }

    }

    @Override
    public void map(final double sourceX,
                    final double sourceY,
                    final int targetX,
                    final int targetY) {

        final int roundedSourceX = (int) (sourceX + 0.5f);
        final int roundedSourceY = (int) (sourceY + 0.5f);

        ImageProcessorWithMasks normalizedSource;
        for (int i = 0; i < normalizedSourceList.size(); i++) {
            normalizedSource = normalizedSourceList.get(i);
            setBlendedIntensity(targetX,
                                targetY,
                                targetList.get(i),
                                targetMaxMaskIntensityList.get(i),
                                normalizedSource.ip.get(roundedSourceX, roundedSourceY),
                                normalizedSource.mask.get(roundedSourceX, roundedSourceY),
                                sourceMaxMaskIntensityList.get(i));
        }

    }

    @Override
    public void mapInterpolated(final double sourceX,
                                final double sourceY,
                                final int targetX,
                                final int targetY) {

        ImageProcessorWithMasks normalizedSource;
        for (int i = 0; i < normalizedSourceList.size(); i++) {
            normalizedSource = normalizedSourceList.get(i);
            setBlendedIntensity(targetX,
                                targetY,
                                targetList.get(i),
                                targetMaxMaskIntensityList.get(i),
                                normalizedSource.ip.getPixelInterpolated(sourceX, sourceY),
                                normalizedSource.mask.getPixelInterpolated(sourceX, sourceY),
                                sourceMaxMaskIntensityList.get(i));
        }
    }

    public void setBlendedIntensity(final int targetX,
                                    final int targetY,
                                    final ImageProcessorWithMasks target,
                                    final double targetMaxMaskIntensity,
                                    final int sourceIntensity,
                                    final int sourceMaskIntensity,
                                    final double sourceMaxMaskIntensity) {

        final double sourceAlpha = sourceMaskIntensity / sourceMaxMaskIntensity;
        final int targetIntensity = target.ip.get(targetX, targetY);
        final double targetAlpha = target.mask.get(targetX, targetY) / targetMaxMaskIntensity;

        final double[] blendedIntensityAndAlpha =
                SingleChannelWithAlphaMapper.getBlendedIntensityAndAlpha(sourceIntensity,
                                                                         sourceAlpha,
                                                                         targetIntensity,
                                                                         targetAlpha);

        target.ip.setf(targetX, targetY, (float) blendedIntensityAndAlpha[0]);
        target.mask.setf(targetX, targetY, (float) (blendedIntensityAndAlpha[1] * targetMaxMaskIntensity));
    }


}

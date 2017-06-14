package org.janelia.alignment.mapper;

import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.ChannelMap;

import static mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

/**
 * TODO: add javadoc
 *
 * @author Eric Trautman
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

        final int roundedSourceX = (int) Math.round(sourceX);
        final int roundedSourceY = (int) Math.round(sourceY);

        ImageProcessorWithMasks normalizedSource;
        for (int i = 0; i < normalizedSourceList.size(); i++) {
            normalizedSource = normalizedSourceList.get(i);
            setBlendedIntensity(targetX,
                                targetY,
                                targetList.get(i),
                                targetMaxMaskIntensityList.get(i),
                                normalizedSource.ip.getf(roundedSourceX, roundedSourceY),
                                normalizedSource.mask.getf(roundedSourceX, roundedSourceY),
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
                                normalizedSource.ip.getInterpolatedPixel(sourceX, sourceY),
                                normalizedSource.mask.getInterpolatedPixel(sourceX, sourceY),
                                sourceMaxMaskIntensityList.get(i));
        }
    }

    public void setBlendedIntensity(final int targetX,
                                    final int targetY,
                                    final ImageProcessorWithMasks target,
                                    final double targetMaxMaskIntensity,
                                    final double sourceIntensity,
                                    final double sourceMaskIntensity,
                                    final double sourceMaxMaskIntensity) {

        final double sourceAlpha = sourceMaskIntensity / sourceMaxMaskIntensity;
        final double targetIntensity = target.ip.getf(targetX, targetY);
        final double targetAlpha = target.mask.getf(targetX, targetY) / targetMaxMaskIntensity;

        final double[] blendedIntensityAndAlpha =
                SingleChannelWithAlphaMapper.getBlendedIntensityAndAlpha(sourceIntensity,
                                                                         sourceAlpha,
                                                                         targetIntensity,
                                                                         targetAlpha);

        target.ip.setf(targetX, targetY, (float) blendedIntensityAndAlpha[0]);
        target.mask.setf(targetX, targetY, (float) (blendedIntensityAndAlpha[1] * targetMaxMaskIntensity));
    }


}

package org.janelia.alignment.mapper;

import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

/**
 * TODO: add javadoc
 *
 * @author Eric Trautman
 */
public class MultiChannelWithAlphaMapper
        extends MultiChannelMapper {

    protected final List<Double> maxMaskIntensityList;

    public MultiChannelWithAlphaMapper(final Map<String, ImageProcessorWithMasks> sourceChannelMap,
                                       final Map<String, ImageProcessor> targetChannelMap,
                                       final int targetOffsetX,
                                       final int targetOffsetY,
                                       final boolean isMappingInterpolated) {

        super(sourceChannelMap, targetChannelMap, targetOffsetX, targetOffsetY, isMappingInterpolated);

        this.maxMaskIntensityList = new ArrayList<>(normalizedSourceList.size());

        if (isMappingInterpolated) {
            for (final ImageProcessorWithMasks normalizedSource : normalizedSourceList) {
                normalizedSource.mask.setInterpolationMethod(ImageProcessor.BILINEAR);
                this.maxMaskIntensityList.add(normalizedSource.mask.getMax());
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
        final int worldTargetX = targetOffsetX + targetX;
        final int worldTargetY = targetOffsetY + targetY;

        ImageProcessorWithMasks normalizedSource;
        for (int i = 0; i < normalizedSourceList.size(); i++) {
            normalizedSource = normalizedSourceList.get(i);
            setBlendedIntensity(worldTargetX,
                                worldTargetY,
                                targetList.get(i),
                                normalizedSource.ip.getPixel(roundedSourceX, roundedSourceY),
                                normalizedSource.mask.getPixel(roundedSourceX, roundedSourceY),
                                maxMaskIntensityList.get(i));
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
        for (int i = 0; i < normalizedSourceList.size(); i++) {
            normalizedSource = normalizedSourceList.get(i);
            setBlendedIntensity(worldTargetX,
                                worldTargetY,
                                targetList.get(i),
                                normalizedSource.ip.getPixelInterpolated(sourceX, sourceY),
                                normalizedSource.mask.getPixelInterpolated(sourceX, sourceY),
                                maxMaskIntensityList.get(i));
        }
    }

    public void setBlendedIntensity(final int worldTargetX,
                                    final int worldTargetY,
                                    final ImageProcessor target,
                                    final int sourceIntensity,
                                    final int sourceMaskIntensity,
                                    final double sourceMaxMaskIntensity) {

        final double sourceAlpha = sourceMaskIntensity / sourceMaxMaskIntensity;
        final int targetIntensity = target.getPixel(worldTargetX, worldTargetY);
        final double targetAlpha = 1.0; // TODO: verify this is okay
        final int blendedIntensity = SingleChannelWithAlphaMapper.getBlendedIntensity(sourceIntensity,
                                                                                      sourceAlpha,
                                                                                      targetIntensity,
                                                                                      targetAlpha);
        target.set(worldTargetX, worldTargetY, blendedIntensity);
    }


}

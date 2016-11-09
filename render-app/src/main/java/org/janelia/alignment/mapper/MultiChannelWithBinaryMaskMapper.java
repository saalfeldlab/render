package org.janelia.alignment.mapper;

import ij.process.ImageProcessor;

import java.util.Map;

import static mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

/**
 * TODO: add javadoc
 *
 * @author Eric Trautman
 */
public class MultiChannelWithBinaryMaskMapper
        extends MultiChannelWithAlphaMapper {

    public MultiChannelWithBinaryMaskMapper(final Map<String, ImageProcessorWithMasks> sourceChannelMap,
                                            final Map<String, ImageProcessor> targetChannelMap,
                                            final int targetOffsetX,
                                            final int targetOffsetY,
                                            final boolean isMappingInterpolated) {

        super(sourceChannelMap, targetChannelMap, targetOffsetX, targetOffsetY, isMappingInterpolated);
    }

    @Override
    public void setBlendedIntensity(final int worldTargetX,
                                    final int worldTargetY,
                                    final ImageProcessor target,
                                    final int sourceIntensity,
                                    final int sourceMaskIntensity,
                                    final double sourceMaxMaskIntensity) {

        if (sourceMaskIntensity > 0.0) {
            target.set(worldTargetX, worldTargetY, sourceIntensity);
        }
    }

}

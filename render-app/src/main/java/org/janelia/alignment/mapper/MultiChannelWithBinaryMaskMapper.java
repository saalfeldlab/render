package org.janelia.alignment.mapper;

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
                                            final Map<String, ImageProcessorWithMasks> targetChannelMap,
                                            final boolean isMappingInterpolated) {

        super(sourceChannelMap, targetChannelMap, isMappingInterpolated);
    }

    @Override
    public void setBlendedIntensity(final int targetX,
                                    final int targetY,
                                    final ImageProcessorWithMasks target,
                                    final double targetMaxMaskIntensity,
                                    final double sourceIntensity,
                                    final double sourceMaskIntensity,
                                    final double sourceMaxMaskIntensity) {

        if (sourceMaskIntensity > 0.0) {
            target.ip.setf(targetX, targetY, (float) sourceIntensity);
            target.mask.setf(targetX, targetY, (float) targetMaxMaskIntensity);
        }
    }

}

package org.janelia.alignment.mapper;

import org.janelia.alignment.ChannelMap;

import static mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

/**
 * TODO: add javadoc
 *
 * @author Eric Trautman
 */
public class MultiChannelWithBinaryMaskMapper
        extends MultiChannelWithAlphaMapper {

    public MultiChannelWithBinaryMaskMapper(final ChannelMap sourceChannels,
                                            final ChannelMap targetChannels,
                                            final boolean isMappingInterpolated) {

        super(sourceChannels, targetChannels, isMappingInterpolated);
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

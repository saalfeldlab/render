package org.janelia.alignment.mapper;

import org.janelia.alignment.ChannelMap;

import static mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

/**
 * Maps source and mask pixels from a multi-channel source to a target canvas and mask.
 * Source regions that overlap with other already mapped sources are NOT blended into the target.
 * The last write (map) simply "wins" for overlapping regions creating a hard edge between tiles.
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
                                    final int sourceIntensity,
                                    final int sourceMaskIntensity,
                                    final double sourceMaxMaskIntensity) {

        if (sourceMaskIntensity > 0.0) {
            target.ip.set(targetX, targetY, sourceIntensity);
            target.mask.set(targetX, targetY, (int) targetMaxMaskIntensity);
        }
    }

}

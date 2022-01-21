package org.janelia.alignment.mapper;

import static mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

/**
 * Maps source and mask pixels from a single channel source to a target canvas and mask.
 * Source regions that overlap with other already mapped sources are NOT blended into the target.
 * The last write (map) simply "wins" for overlapping regions creating a hard edge between tiles.
 */
public class SingleChannelWithBinaryMaskMapper
        extends SingleChannelWithAlphaMapper {

    public SingleChannelWithBinaryMaskMapper(final ImageProcessorWithMasks source,
                                             final ImageProcessorWithMasks target,
                                             final boolean isMappingInterpolated) {

        super(source, target, isMappingInterpolated);
    }

    @Override
    public void setBlendedIntensity(final int worldTargetX,
                                    final int worldTargetY,
                                    final int sourceIntensity,
                                    final int sourceMaskIntensity) {

        if (sourceMaskIntensity > 0.0) {
            target.ip.set(worldTargetX, worldTargetY, sourceIntensity);
            target.mask.set(worldTargetX, worldTargetY, (int) targetMaxMaskIntensity);
        }
    }

}

package org.janelia.alignment.mapper;

import static mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

/**
 * TODO: add javadoc
 *
 * @author Eric Trautman
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
                                    final double sourceIntensity,
                                    final double sourceMaskIntensity) {

        if (sourceMaskIntensity > 0.0) {
            target.ip.setf(worldTargetX, worldTargetY, (float) sourceIntensity);
            target.mask.setf(worldTargetX, worldTargetY, (float) targetMaxMaskIntensity);
        }
    }

}

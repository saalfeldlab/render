package org.janelia.alignment.mapper;

import ij.process.ImageProcessor;

import static mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

/**
 * TODO: add javadoc
 *
 * @author Eric Trautman
 */
public class SingleChannelWithBinaryMaskMapper
        extends SingleChannelWithAlphaMapper {

    public SingleChannelWithBinaryMaskMapper(final ImageProcessorWithMasks source,
                                             final ImageProcessor target,
                                             final int targetOffsetX,
                                             final int targetOffsetY,
                                             final boolean isMappingInterpolated) {

        super(source, target, targetOffsetX, targetOffsetY, isMappingInterpolated);
    }

    @Override
    public void setBlendedIntensity(final int worldTargetX,
                                    final int worldTargetY,
                                    final int sourceIntensity,
                                    final int sourceMaskIntensity) {

        if (sourceMaskIntensity > 0.0) {
            target.set(worldTargetX, worldTargetY, sourceIntensity);
        }
    }

}

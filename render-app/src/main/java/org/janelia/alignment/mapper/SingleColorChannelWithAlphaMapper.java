package org.janelia.alignment.mapper;

import static mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

/**
 * Maps source and mask pixels from a single channel source to a target canvas and mask
 * using bi-linear interpolation.  Source regions that overlap with other already mapped sources
 * are blended into the target.
 */
public class SingleColorChannelWithAlphaMapper
        extends SingleChannelWithAlphaMapper {

    public SingleColorChannelWithAlphaMapper(final ImageProcessorWithMasks source,
                                             final ImageProcessorWithMasks target,
                                             final boolean isMappingInterpolated) {
        super(source, target, isMappingInterpolated);
    }

    @Override
    public void setBlendedIntensity(final int targetX,
                                    final int targetY,
                                    final int sourceIntensity,
                                    final int sourceMaskIntensity) {

        final int sourceR = (sourceIntensity & 0xff0000) >> 16;
        final int sourceG = (sourceIntensity & 0xff00) >> 8;
        final int sourceB = sourceIntensity & 0xff;

        final double sourceAlpha = sourceMaskIntensity / sourceMaxMaskIntensity;

        final int targetIntensity = target.ip.get(targetX, targetY);

        final int blendedIntensity;
        final double blendedAlpha;

        if (targetIntensity == 0) {

            final int blendedR = (int) ((sourceR * sourceAlpha) + 0.5);
            final int blendedG = (int) ((sourceG * sourceAlpha) + 0.5);
            final int blendedB = (int) ((sourceB * sourceAlpha) + 0.5);

            blendedIntensity = (blendedR << 16) + (blendedG << 8) + blendedB;
            blendedAlpha = sourceAlpha;

        } else {

            final double targetAlpha =
                    (target.mask.get(targetX, targetY) / targetMaxMaskIntensity) * (1 - sourceAlpha);

            blendedAlpha = sourceAlpha + targetAlpha;

            if (blendedAlpha == 0) {
                blendedIntensity = 0;
            } else {
                final int targetR = (targetIntensity & 0xff0000) >> 16;
                final int targetG = (targetIntensity & 0xff00) >> 8;
                final int targetB = targetIntensity & 0xff;

                final int blendedR = (int) ((((sourceR * sourceAlpha) + (targetR * targetAlpha)) / blendedAlpha) + 0.5);
                final int blendedG = (int) ((((sourceG * sourceAlpha) + (targetG * targetAlpha)) / blendedAlpha) + 0.5);
                final int blendedB = (int) ((((sourceB * sourceAlpha) + (targetB * targetAlpha)) / blendedAlpha) + 0.5);

                blendedIntensity = (blendedR << 16) + (blendedG << 8) + blendedB;
            }
        }

        target.ip.set(targetX, targetY, blendedIntensity);
        target.mask.set(targetX, targetY, (int) (blendedAlpha  * targetMaxMaskIntensity));
    }

}

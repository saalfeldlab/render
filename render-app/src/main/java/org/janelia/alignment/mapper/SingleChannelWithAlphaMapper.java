package org.janelia.alignment.mapper;

import ij.process.ImageProcessor;

import static mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

/**
 * TODO: add javadoc
 *
 * @author Eric Trautman
 */
public class SingleChannelWithAlphaMapper
        extends SingleChannelMapper {

    protected final double maxMaskIntensity;

    public SingleChannelWithAlphaMapper(final ImageProcessorWithMasks source,
                                        final ImageProcessor target,
                                        final int targetOffsetX,
                                        final int targetOffsetY,
                                        final boolean isMappingInterpolated) {

        super(source, target, targetOffsetX, targetOffsetY, isMappingInterpolated);

        if (isMappingInterpolated) {
            this.normalizedSource.mask.setInterpolationMethod(ImageProcessor.BILINEAR);
        }

        this.maxMaskIntensity = this.normalizedSource.mask.getMax();
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

        setBlendedIntensity(worldTargetX,
                            worldTargetY,
                            normalizedSource.ip.getPixel(roundedSourceX, roundedSourceY),
                            normalizedSource.mask.getPixel(roundedSourceX, roundedSourceY));
    }

    @Override
    public void mapInterpolated(final double sourceX,
                                final double sourceY,
                                final int targetX,
                                final int targetY) {

        final int worldTargetX = targetOffsetX + targetX;
        final int worldTargetY = targetOffsetY + targetY;

        setBlendedIntensity(worldTargetX,
                            worldTargetY,
                            normalizedSource.ip.getPixelInterpolated(sourceX, sourceY),
                            normalizedSource.mask.getPixelInterpolated(sourceX, sourceY));
    }

    public void setBlendedIntensity(final int worldTargetX,
                                    final int worldTargetY,
                                    final int sourceIntensity,
                                    final int sourceMaskIntensity) {

        final double sourceAlpha = sourceMaskIntensity / maxMaskIntensity;
        final int targetIntensity = target.getPixel(worldTargetX, worldTargetY);
        final double targetAlpha = 1.0; // TODO: verify this is okay
        final int blendedIntensity = getBlendedIntensity(sourceIntensity, sourceAlpha, targetIntensity, targetAlpha);
        target.set(worldTargetX, worldTargetY, blendedIntensity);
    }

    public static int getBlendedIntensity(final int sourceIntensity,
                                          final double sourceAlpha,
                                          final int targetIntensity,
                                          final double targetAlpha) {

        final int blendedIntensity;

        if (targetIntensity == 0) {

            blendedIntensity = (int) ((sourceIntensity * sourceAlpha) + 0.5);

        } else {

            final double blendedAlpha = sourceAlpha + (targetAlpha * (1 - sourceAlpha));

            if (blendedAlpha == 0) {
                blendedIntensity = 0;
            } else {
                blendedIntensity = (int) (
                        (((sourceIntensity * sourceAlpha) + (targetIntensity * targetAlpha * (1 - sourceAlpha))) /
                         blendedAlpha)
                        + 0.5);
            }
        }

        return blendedIntensity;
    }

}

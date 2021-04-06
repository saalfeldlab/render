package org.janelia.alignment.mapper;

import ij.process.ImageProcessor;

import static mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

/**
 * Maps source and mask pixels from a single channel source to a target canvas and mask.
 * Source regions that overlap with other already mapped sources are blended into the target.
 */
public class SingleChannelWithAlphaMapper
        extends SingleChannelMapper {

    protected final double sourceMaxMaskIntensity;
    protected final double targetMaxMaskIntensity;

    public SingleChannelWithAlphaMapper(final ImageProcessorWithMasks source,
                                        final ImageProcessorWithMasks target,
                                        final boolean isMappingInterpolated) {

        super(source, target, isMappingInterpolated);

        if (isMappingInterpolated) {
            this.normalizedSource.mask.setInterpolationMethod(ImageProcessor.BILINEAR);
        }

        this.sourceMaxMaskIntensity = this.normalizedSource.mask.getMax();
        this.targetMaxMaskIntensity = this.target.mask.getMax();
    }

    @Override
    public void map(final double sourceX,
                    final double sourceY,
                    final int targetX,
                    final int targetY) {

        final int roundedSourceX = (int) (sourceX + 0.5f);
        final int roundedSourceY = (int) (sourceY + 0.5f);
        setBlendedIntensity(targetX,
                            targetY,
                            normalizedSource.ip.get(roundedSourceX, roundedSourceY),
                            normalizedSource.mask.get(roundedSourceX, roundedSourceY));
    }

    @Override
    public void mapInterpolated(final double sourceX,
                                final double sourceY,
                                final int targetX,
                                final int targetY) {

        setBlendedIntensity(targetX,
                            targetY,
                            normalizedSource.ip.getPixelInterpolated(sourceX, sourceY),
                            normalizedSource.mask.getPixelInterpolated(sourceX, sourceY));
    }

    public void setBlendedIntensity(final int targetX,
                                    final int targetY,
                                    final int sourceIntensity,
                                    final int sourceMaskIntensity) {

        final double sourceAlpha = sourceMaskIntensity / sourceMaxMaskIntensity;
        final int targetIntensity = target.ip.get(targetX, targetY);
        final double targetAlpha = target.mask.get(targetX, targetY) / targetMaxMaskIntensity;

        final double[] blendedIntensityAndAlpha =
                getBlendedIntensityAndAlpha(sourceIntensity, sourceAlpha, targetIntensity, targetAlpha);

        target.ip.setf(targetX, targetY, (float) blendedIntensityAndAlpha[0]);
        target.mask.setf(targetX, targetY, (float) (blendedIntensityAndAlpha[1] * targetMaxMaskIntensity));
    }

    public static double[] getBlendedIntensityAndAlpha(final double sourceIntensity,
                                                       final double sourceAlpha,
                                                       final double targetIntensity,
                                                       final double targetAlpha) {

        final double blendedIntensity;
        final double blendedAlpha;

        if (targetIntensity == 0) {

            blendedIntensity = sourceIntensity * sourceAlpha;
            blendedAlpha = sourceAlpha;

        } else {

            blendedAlpha = sourceAlpha + (targetAlpha * (1 - sourceAlpha));

            if (blendedAlpha == 0) {
                blendedIntensity = 0;
            } else {
                blendedIntensity =
                        ((sourceIntensity * sourceAlpha) + (targetIntensity * targetAlpha * (1 - sourceAlpha))) /
                        blendedAlpha;
            }
        }

        return new double[] { blendedIntensity, blendedAlpha };
    }

}

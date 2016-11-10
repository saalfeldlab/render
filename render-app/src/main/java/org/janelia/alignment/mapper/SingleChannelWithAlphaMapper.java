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

        final int roundedSourceX = (int) Math.round(sourceX);
        final int roundedSourceY = (int) Math.round(sourceY);

        setBlendedIntensity(targetX,
                            targetY,
                            normalizedSource.ip.getf(roundedSourceX, roundedSourceY),
                            normalizedSource.mask.getf(roundedSourceX, roundedSourceY));
    }

    @Override
    public void mapInterpolated(final double sourceX,
                                final double sourceY,
                                final int targetX,
                                final int targetY) {

        setBlendedIntensity(targetX,
                            targetY,
                            normalizedSource.ip.getInterpolatedPixel(sourceX, sourceY),
                            normalizedSource.mask.getInterpolatedPixel(sourceX, sourceY));
    }

    public void setBlendedIntensity(final int targetX,
                                    final int targetY,
                                    final double sourceIntensity,
                                    final double sourceMaskIntensity) {

        final double sourceAlpha = sourceMaskIntensity / sourceMaxMaskIntensity;
        final double targetIntensity = target.ip.getf(targetX, targetY);
        final double targetAlpha = target.mask.getf(targetX, targetY) / targetMaxMaskIntensity;

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

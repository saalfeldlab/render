package org.janelia.alignment.mapper;

import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import static mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

/**
 * TODO: add javadoc
 *
 * @author Eric Trautman
 */
public class SingleChannelMapper
        implements PixelMapper {

    protected final ImageProcessorWithMasks normalizedSource;
    protected final ImageProcessorWithMasks target;
    protected final boolean isMappingInterpolated;

    public SingleChannelMapper(final ImageProcessorWithMasks source,
                               final ImageProcessorWithMasks target,
                               final boolean isMappingInterpolated) {

        this.normalizedSource = normalizeSourceForTarget(source, target.ip);
        this.target = target;
        this.isMappingInterpolated = isMappingInterpolated;

        if (isMappingInterpolated) {
            this.normalizedSource.ip.setInterpolationMethod(ImageProcessor.BILINEAR);
        }
    }

    @Override
    public int getTargetWidth() {
        return target.getWidth();
    }

    @Override
    public int getTargetHeight() {
        return target.getHeight();
    }

    @Override
    public boolean isMappingInterpolated() {
        return isMappingInterpolated;
    }

    @Override
    public void map(final double sourceX,
                    final double sourceY,
                    final int targetX,
                    final int targetY) {

        final int roundedSourceX = (int) Math.round(sourceX);
        final int roundedSourceY = (int) Math.round(sourceY);

        target.ip.setf(targetX, targetY, normalizedSource.ip.getf(roundedSourceX, roundedSourceY));
    }

    @Override
    public void mapInterpolated(final double sourceX,
                                final double sourceY,
                                final int targetX,
                                final int targetY) {

        target.ip.setf(targetX, targetY, (float) normalizedSource.ip.getInterpolatedPixel(sourceX, sourceY));
    }

    public static ImageProcessorWithMasks normalizeSourceForTarget(final ImageProcessorWithMasks source,
                                                                   final ImageProcessor target)
            throws IllegalArgumentException {

        final ImageProcessorWithMasks normalizedSource;

        if (target instanceof ByteProcessor) {
            normalizedSource =
                    new ImageProcessorWithMasks(source.ip.convertToByteProcessor(),
                                                source.mask,
                                                null);
        } else if (target instanceof ShortProcessor) {
            normalizedSource =
                    new ImageProcessorWithMasks(source.ip.convertToShortProcessor(),
                                                source.mask,
                                                null);
        } else if (target instanceof FloatProcessor) {
            normalizedSource =
                    new ImageProcessorWithMasks(source.ip.convertToFloatProcessor(),
                                                source.mask,
                                                null);
        } else if (target instanceof ColorProcessor) {
            normalizedSource =
                    new ImageProcessorWithMasks(source.ip.convertToColorProcessor(),
                                                source.mask,
                                                null);
        } else {
            throw new IllegalArgumentException("conversion to " + target.getClass() + " is not supported");
        }

        return normalizedSource;
    }

}

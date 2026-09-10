package org.janelia.alignment.mapper;

import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import static mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

/**
 * Maps source pixels from an unmasked single channel source to a target canvas.
 */
public class SingleChannelMapper
        implements PixelMapper {

    protected final ImageProcessorWithMasks normalizedSource;
    protected final ImageProcessorWithMasks target;
    protected final boolean isMappingInterpolated;

    // source and (when present) mask share the same dimensions (enforced when tiles are loaded),
    // so this bound covers interpolated sampling of either; computed once (not per pixel).
    protected final double sourceMaxX;
    protected final double sourceMaxY;

    public SingleChannelMapper(final ImageProcessorWithMasks source,
                               final ImageProcessorWithMasks target,
                               final boolean isMappingInterpolated) {

        this.normalizedSource = normalizeSourceForTarget(source, target.ip);
        this.target = target;
        this.isMappingInterpolated = isMappingInterpolated;
        this.sourceMaxX = PixelMapper.safeMaxInterpolationCoordinate(normalizedSource.ip.getWidth());
        this.sourceMaxY = PixelMapper.safeMaxInterpolationCoordinate(normalizedSource.ip.getHeight());

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

        final int roundedSourceX = (int) (sourceX + 0.5f);
        final int roundedSourceY = (int) (sourceY + 0.5f);
        target.ip.set(targetX, targetY, normalizedSource.ip.get(roundedSourceX, roundedSourceY));
    }

    @Override
    public void mapInterpolated(final double sourceX,
                                final double sourceY,
                                final int targetX,
                                final int targetY) {

        final double clampedX = PixelMapper.clampInterpolationCoordinate(sourceX, sourceMaxX);
        final double clampedY = PixelMapper.clampInterpolationCoordinate(sourceY, sourceMaxY);
        target.ip.set(targetX, targetY, normalizedSource.ip.getPixelInterpolated(clampedX, clampedY));
    }

    public static ImageProcessorWithMasks normalizeSourceForTarget(final ImageProcessorWithMasks source,
                                                                   final ImageProcessor target)
            throws IllegalArgumentException {

        final ImageProcessorWithMasks normalizedSource;

        if (target.getClass().equals(source.ip.getClass())) {
            normalizedSource = source; // no need to normalize
        } else if (target instanceof ByteProcessor) {
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

package org.janelia.alignment.mapper;

import ij.process.ImageProcessor;

/**
 * TODO: add javadoc
 *
 * @author Eric Trautman
 */
public interface PixelMapper {

    /**
     * {@link ImageProcessor#getPixelInterpolated} returns 0 (black/transparent) for any sample whose
     * coordinate falls outside the image (e.g. for an image of width 2000, any x in [1999, 2000] is
     * treated as "out of range" and zeroed instead of being interpolated against the last valid column).
     * This case is hit for nearly all transforms so that entire edges render as black. Clamp the sample
     * to the last coordinate that still lands inside the valid domain.
     * <p>
     * Takes the pre-computed safe bound (see {@link #safeMaxInterpolationCoordinate}) rather than deriving
     * it here, so the per-pixel hot path is just two comparisons &mdash; no {@code getWidth()}/
     * {@code getHeight()} dispatch.
     *
     * @param  coordinate         source x or y coordinate.
     * @param  safeMaxCoordinate  last coordinate on this axis that {@code getPixelInterpolated} will not
     *                            zero out (see {@link #safeMaxInterpolationCoordinate}).
     *
     * @return the coordinate, clamped to {@code safeMaxCoordinate} if necessary.
     */
    static double clampInterpolationCoordinate(final double coordinate,
                                               final double safeMaxCoordinate) {
        if (coordinate < 0) {
            return 0;
        } else if (coordinate > safeMaxCoordinate) {
            return safeMaxCoordinate;
        }
        return coordinate;
    }

    /**
     * @param  sizeOnAxis  source image width or height.
     *
     * @return the largest coordinate on that axis for which {@link ImageProcessor#getPixelInterpolated}
     *         will not incorrectly return 0; pass this once per source (not per pixel) to
     *         {@link #clampInterpolationCoordinate}.
     */
    static double safeMaxInterpolationCoordinate(final int sizeOnAxis) {
        return Math.nextDown(sizeOnAxis - 1.0);
    }

    /**
     * @return width of the mapped local target.
     */
    int getTargetWidth();

    /**
     * @return height of the mapped local target.
     */
    int getTargetHeight();

    /**
     * @return true if the {@link #mapInterpolated} method should be used for mapping;
     *         false if the {@link #map} method should be used for mapping.
     */
    boolean isMappingInterpolated();

    /**
     * Maps value for pixel ((int) sourceX + 0.5, (int) sourceY + 0.5) to pixel (targetX, targetY).
     *
     * @param  sourceX  source x coordinate.
     * @param  sourceY  source y coordinate.
     * @param  targetX  local target x coordinate.
     * @param  targetY  local target y coordinate.
     */
    void map(final double sourceX,
             final double sourceY,
             final int targetX,
             final int targetY);

    /**
     * Maps value for pixel (sourceX, sourceY) to pixel (targetX, targetY).
     * Uses the current interpolation method to find the pixel value at real coordinates (sourceX, sourceY).
     *
     * @param  sourceX  source x coordinate.
     * @param  sourceY  source y coordinate.
     * @param  targetX  local target x coordinate.
     * @param  targetY  local target y coordinate.
     */
    void mapInterpolated(final double sourceX,
                         final double sourceY,
                         final int targetX,
                         final int targetY);

    /**
     * Maps values for horizontal ranges of target pixels.
     */
    interface LineMapper {
        /**
         * Maps value for a line of pixels in the target {@code (targetX + i, targetY)}, where {@code 0 <= i < length}.
         * <p>
         * Stepping one pixel in X in the target, means stepping {@code (sourceStepX, sourceStepY)} in the source.
         * That is, target pixel {@code (targetX + i, targetY)} corresponds to source pixel {@code (sourceX + i * sourceStepX, sourceY + i * sourceStepY)}.
         * <p>
         * The interpolation method is determined when constructing the {@code LineMapper} instance is constructed (see {@link #createLineMapper(boolean)}).
         *
         * @param sourceX     source X coordinate.
         * @param sourceY     source Y coordinate.
         * @param sourceStepX source X offset corresponding to stepping 1 pixel in X in the target.
         * @param sourceStepY source Y offset corresponding to stepping 1 pixel in X in the target.
         * @param targetX     local target X coordinate.
         * @param targetY     local target Y coordinate.
         * @param length      number of pixels to map.
         */
        void map(double sourceX, double sourceY, double sourceStepX, double sourceStepY, int targetX, int targetY, int length);
    }

    /**
     * Create a {@code LineMapper}.
     * <p>
     * If {@link #isMappingInterpolated()}{@code ==true} the {@code LineMapper} will use linear interpolation,
     * otherwise nearest-neighbor interpolation.
     *
     * @return a new {@code LineMapper}
     */
    default LineMapper createLineMapper() {
        return createLineMapper(isMappingInterpolated());
    }

    /**
     * Create a {@code LineMapper}.
     *
     * @param isMappingInterpolated if {@code true} the {@code LineMapper} will use linear interpolation,
     *                              if {@code false} the {@code LineMapper} will use nearest-neighbor interpolation.
     * @return a new {@code LineMapper}
     */
    default LineMapper createLineMapper(final boolean isMappingInterpolated) {
        if (isMappingInterpolated) {
            return (sx, sy, sdx, sdy, tx, ty, length) -> {
                for (int x = tx; x < (tx + length); ++x) {
                    PixelMapper.this.mapInterpolated(sx, sy, x, ty);
                    sx += sdx;
                    sy += sdy;
                }
            };
        } else {
            return (sx, sy, sdx, sdy, tx, ty, length) -> {
                for (int x = tx; x < (tx + length); ++x) {
                    PixelMapper.this.map(sx, sy, x, ty);
                    sx += sdx;
                    sy += sdy;
                }
            };
        }
    }
}

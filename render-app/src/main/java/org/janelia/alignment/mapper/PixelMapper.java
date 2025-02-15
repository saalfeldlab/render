package org.janelia.alignment.mapper;

/**
 * TODO: add javadoc
 *
 * @author Eric Trautman
 */
public interface PixelMapper {

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

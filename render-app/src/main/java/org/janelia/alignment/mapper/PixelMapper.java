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

}
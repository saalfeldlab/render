package org.janelia.render.client.parameter;

import java.io.Serializable;

import org.janelia.alignment.spec.Bounds;

import com.beust.jcommander.Parameter;

/**
 * Parameters for specifying xy ranges.
 *
 * @author Stephan Preibisch
 */
public class XYRangeParameters
        implements Serializable {

    @Parameter(
            names = "--minX",
            description = "Minimum X value within layers to be processed")
    public Double minX;

    @Parameter(
            names = "--maxX",
            description = "Maximum X value within layers to be processed")
    public Double maxX;

    @Parameter(
            names = "--minY",
            description = "Minimum Y value within layers to be processed")
    public Double minY;

    @Parameter(
            names = "--maxY",
            description = "Maximum Y value within layers to be processed")
    public Double maxY;

    public Bounds overrideBounds(final Bounds defaultBounds) {
        return new Bounds(
                          minX == null ? defaultBounds.getMinX() : Math.max(minX, defaultBounds.getMinX()),
                          minY == null ? defaultBounds.getMinY() : Math.max(minY, defaultBounds.getMinY()),
                          defaultBounds.getMinZ(),
                          maxX == null ? defaultBounds.getMaxX() : Math.min(maxX, defaultBounds.getMaxX()),
                          maxY == null ? defaultBounds.getMaxY() : Math.min(maxY, defaultBounds.getMaxY()),
                          defaultBounds.getMaxZ() );
    }

}

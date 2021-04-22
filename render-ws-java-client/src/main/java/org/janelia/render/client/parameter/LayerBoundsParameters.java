package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;

import java.io.Serializable;

import org.janelia.alignment.spec.Bounds;

/**
 * Parameters for specifying layer bounds.
 *
 * @author Eric Trautman
 */
public class LayerBoundsParameters implements Serializable {

    @Parameter(
            names = "--minX",
            description = "Minimum X value for all tiles")
    public Double minX;

    @Parameter(
            names = "--maxX",
            description = "Maximum X value for all tiles")
    public Double maxX;

    @Parameter(
            names = "--minY",
            description = "Minimum Y value for all tiles")
    public Double minY;

    @Parameter(
            names = "--maxY",
            description = "Maximum Y value for all tiles")
    public Double maxY;

    public boolean isDefined() {
        return (minX != null) || (maxX != null) || (minY != null) || (maxY != null);
    }

    public void validate() throws IllegalArgumentException {

        if (isDefined()) {

            if ((minX == null) || (maxX == null) || (minY == null) || (maxY == null)) {
                throw new IllegalArgumentException("since one or more of minX (" + minX + "), maxX (" + maxX +
                                                   "), minY (" + minY + "), maxY (" + maxY +
                                                   ") is specified, all must be specified");
            }

            if (minX > maxX) {
                throw new IllegalArgumentException("minX (" + minX + ") is greater than maxX (" + maxX + ")");
            }

            if (minY > maxY) {
                throw new IllegalArgumentException("minY (" + minY + ") is greater than maxY (" + maxY + ")");
            }
        }

    }

    public Bounds overrideBounds(final Bounds defaultBounds) {
        return new Bounds(minX == null ? defaultBounds.getMinX() : minX,
                          minY == null ? defaultBounds.getMinY() : minY,
                          defaultBounds.getMinZ(),
                          maxX == null ? defaultBounds.getMaxX() : maxX,
                          maxY == null ? defaultBounds.getMaxY() : maxY,
                          defaultBounds.getMaxZ());
    }
}

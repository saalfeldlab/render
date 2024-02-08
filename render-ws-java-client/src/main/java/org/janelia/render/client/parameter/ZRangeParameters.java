package org.janelia.render.client.parameter;

import java.io.Serializable;

import org.janelia.alignment.spec.Bounds;

import com.beust.jcommander.Parameter;

/**
 * Parameters for specifying layer z ranges.
 *
 * @author Eric Trautman
 */
public class ZRangeParameters
        implements Serializable {

    @Parameter(
            names = "--minZ",
            description = "Minimum Z value for layers to be processed")
    public Double minZ;

    @Parameter(
            names = "--maxZ",
            description = "Maximum Z value for layers to be processed")
    public Double maxZ;

    public ZRangeParameters() {
    }

    public ZRangeParameters(final Double minZ,
                            final Double maxZ) {
        this.minZ = minZ;
        this.maxZ = maxZ;
    }

    public Bounds overrideBounds(final Bounds defaultBounds) {
        return new Bounds(defaultBounds.getMinX(),
                          defaultBounds.getMinY(),
                          minZ == null ? defaultBounds.getMinZ() : Math.max(minZ, defaultBounds.getMinZ()),
                          defaultBounds.getMaxX(),
                          defaultBounds.getMaxY(),
                          maxZ == null ? defaultBounds.getMaxZ() : Math.min(maxZ, defaultBounds.getMaxZ()));
    }

    @Override
    public String toString() {
        return "{minZ=" + minZ +
               ", maxZ=" + maxZ +
               '}';
    }
}

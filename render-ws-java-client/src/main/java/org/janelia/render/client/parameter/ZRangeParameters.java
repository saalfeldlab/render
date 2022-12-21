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

    public Bounds overrideBounds(final Bounds defaultBounds) {
        return new Bounds(defaultBounds.getMinX(),
                          defaultBounds.getMinY(),
                          minZ == null ? defaultBounds.getMinZ() : minZ,
                          defaultBounds.getMaxX(),
                          defaultBounds.getMaxY(),
                          maxZ == null ? defaultBounds.getMaxZ() : maxZ);
    }

}

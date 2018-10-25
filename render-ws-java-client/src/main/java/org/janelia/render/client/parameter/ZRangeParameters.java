package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;

import java.io.Serializable;

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

}

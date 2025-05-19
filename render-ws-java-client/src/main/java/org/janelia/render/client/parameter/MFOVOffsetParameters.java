package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;

import java.io.Serializable;

/**
 * Parameters for building MFOV offset stacks.
 */
public class MFOVOffsetParameters
        implements Serializable {

    @Parameter(
            names = "--mopMinimumNumberOfTilesForIncludedMFOVs",
            description = "Only include MFOVs with this many tiles (or more).  Omit to include all MFOVs.")
    public Integer minimumNumberOfTilesForIncludedMFOVs;

    @Parameter(
            names = "--mopMaxNeighborPixelDistance",
            description = "Maximum distance in pixels from each best connected tile to search for neighbors.")
    public Integer maxNeighborPixelDistance;

    @Parameter(
            names = "--mopMaxNeighborCount",
            description = "Maximum number of neighbors to include in each best connected list.")
    public Integer maxNeighborCount;

    @Parameter(
            names = "--mopOffsetStackSuffix",
            description = "Suffix to append to the source stack name when creating the offset stack name.")
    public String offsetStackSuffix = "_os";

    public MFOVOffsetParameters() {
    }
}

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
            description = "Maximum distance in pixels from each best connected tile to search for neighbors.",
            required = true)
    public Integer maxNeighborPixelDistance;

    @Parameter(
            names = "--mopMaxNeighborCount",
            description = "Maximum number of neighbors to include in each best connected list.",
            required = true)
    public Integer maxNeighborCount;

    @Parameter(
            names = "--mopRenderScale",
            description = "Scale for rendered tiles used in matching.",
            required = true)
    public Double renderScale;

    @Parameter(
            names = "--mopMinNumberOfMatchInliers",
            description = "Minimum number of inliers required (e.g. 10) to consider a cross layer tile pair successfully matched.",
            required = true)
    public Integer minNumberOfMatchInliers;

    @Parameter(
            names = "--mopMaxAbsoluteMFOVTranslationDelta",
            description = "Maximum allowed x or y translation pixel difference between MFOVs in the same z layer (e.g. 50).  " +
                          "Omit to skip translation consistency check.")
    public Integer maxAbsoluteMFOVTranslationDelta;

    @Parameter(
            names = "--mopOffsetStackSuffix",
            description = "Suffix to append to the source stack name when creating the offset stack name.")
    public String offsetStackSuffix = "_os";

    public MFOVOffsetParameters() {
    }
}

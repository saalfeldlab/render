package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;

import java.io.Serializable;

/**
 * Parameters for building stacks with MFOV tiles.
 */
public class MFOVAsTileParameters
        implements Serializable {

    @Parameter(
            names = "--mfovTileRenderScale",
            description = "Scale for rendered SFOVs when creating the MFOV tiles.  " +
                          "The default value of 0.1 works well because MFOVs have 91 SFOVs, " +
                          "so each MFOV tile image will be roughly the same size as an original SFOV.",
            required = true)
    public Double mfovTileRenderScale = 0.1;

    @Parameter(
            names = "--mfovTileStackSuffix",
            description = "Suffix to append to the source stack name when creating the MFOV as tile stack name.")
    public String mfovTileStackSuffix = "_mt";

    public MFOVAsTileParameters() {
    }
}

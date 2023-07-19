package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;

import java.io.Reader;
import java.io.Serializable;

import org.janelia.alignment.json.JsonUtils;

/**
 * Parameters for finding and storing unconnected MFOVs.
 *
 * @author Eric Trautman
 */
public class UnconnectedCrossMFOVParameters
        implements Serializable {

    @Parameter(
            names = "--montageStackSuffix",
            description = "Suffix to append to source stack names when creating mfov montage stack names")
    public String montageStackSuffix = "_mfov_montage";

    @Parameter(
            names = "--minPairsForConnection",
            description = "Minimum number of connected SFOV tile pairs needed to consider an MFOV connected",
            variableArity = true,
            required = true)
    public Integer minPairsForConnection;

    @Parameter(
            names = "--unconnectedMFOVPairsDirectory",
            description = "Directory to store unconnected MFOV pair results (omit to simply print results to stdout)"
    )
    public String unconnectedMFOVPairsDirectory;

    public UnconnectedCrossMFOVParameters() {
    }

    /*
     * @return JSON representation of these parameters.
     */
    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    public static UnconnectedCrossMFOVParameters fromJson(final Reader json) {
        return JSON_HELPER.fromJson(json);
    }

    private static final JsonUtils.Helper<UnconnectedCrossMFOVParameters> JSON_HELPER =
            new JsonUtils.Helper<>(UnconnectedCrossMFOVParameters.class);
}

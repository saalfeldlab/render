package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;

import java.io.Reader;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.stack.StackWithZValues;

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

    @Parameter(
            names = "--numberOfStacksPerBatch",
            description = "Number of stacks to process in each batch when using spark")
    public int numberOfStacksPerBatch = 1;

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

    /**
     * @return the specified StackWithZValues list bundled into groups of numberOfStacksPerBatch.
     */
    public List<List<StackWithZValues>> bundleStacks(final List<StackWithZValues> stackList) {
        final List<List<StackWithZValues>> bundles = new ArrayList<>();
        List<StackWithZValues> currentBundle = new ArrayList<>();
        for (final StackWithZValues stack : stackList) {
            currentBundle.add(stack);
            if (currentBundle.size() == numberOfStacksPerBatch) {
                bundles.add(currentBundle);
                currentBundle = new ArrayList<>();
            }
        }
        if (! currentBundle.isEmpty()) {
            bundles.add(currentBundle);
        }
        return bundles;
    }

    private static final JsonUtils.Helper<UnconnectedCrossMFOVParameters> JSON_HELPER =
            new JsonUtils.Helper<>(UnconnectedCrossMFOVParameters.class);
}

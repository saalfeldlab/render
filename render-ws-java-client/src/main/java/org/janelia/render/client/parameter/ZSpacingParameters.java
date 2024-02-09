package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.FileNotFoundException;
import java.io.Serializable;

import org.janelia.render.client.zspacing.HeadlessZPositionCorrection;
import org.janelia.render.client.zspacing.loader.ResinMaskParameters;
import org.janelia.thickness.inference.Options;

/**
 * Parameters for cross correlation based z-layer thickness correction.
 *
 * @author Eric Trautman
 */
public class ZSpacingParameters
        implements Serializable {

    // TODO: refactor this class (and possibly the clients that use it) to separate cross correlation
    //       from thickness correction since we now use cross correlation independently for quality control

    @Parameter(
            names = "--scale",
            description = "Scale to render each layer",
            required = true)
    public Double scale;

    @Parameter(
            names = "--rootDirectory",
            description = "Root directory for all output (e.g. /groups/flyem/data/alignment-ett/zcorr)",
            required = true)
    public String rootDirectory;

    @Parameter(
            names = "--runName",
            description = "Common run name to include in output path when running array jobs.  " +
                          "Typically includes the timestamp when the array job was created.  " +
                          "Omit if not running in an array job context.")
    public String runName;

    @Parameter(
            names = "--nLocalEstimates",
            description = "Number of local estimates")
    public Integer nLocalEstimates = 1;

    @ParametersDelegate
    public ResinMaskParameters resin = new ResinMaskParameters();

    @Parameter(
            names = "--normalizedEdgeLayerCount",
            description = "The number of layers at the beginning and end of the stack to assign correction " +
                          "delta values of 1.0.  This hack fixes the stretched or squished corrections the " +
                          "solver typically produces for layers at the edges of the stack.  " +
                          "For Z0720_07m stacks, we set this value to 30.  " +
                          "Omit to leave the solver correction values as is.")
    public Integer normalizedEdgeLayerCount;

    @Parameter(
            names = "--solveExisting",
            description = "Specify to load existing correlation data and solve.",
            arity = 0)
    public boolean solveExisting;

    @Parameter(
            names = "--skipSolve",
            description = "Specify to simply derive cross correlation data and skip solve " +
                          "(will be ignored if --solveExisting is also specified).",
            arity = 0)
    public boolean skipSolve;

    @Parameter(
            names = "--optionsJson",
            description = "JSON file containing thickness correction options (omit to use default values)")
    public String optionsJson;

    public Options inferenceOptions;

    public Options getInferenceOptions()
            throws FileNotFoundException {
        Options options = this.inferenceOptions;
        if (options == null) {
            if(optionsJson == null) {
                options = HeadlessZPositionCorrection.generateDefaultFIBSEMOptions();
            } else {
                options = Options.read(optionsJson);
            }
        }
        return options;
    }

    @JsonIgnore
    public int getComparisonRange()
            throws FileNotFoundException {
        return getInferenceOptions().comparisonRange;
    }
}

package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import java.io.Serializable;

/**
 * Parameters for correcting multi-SEM image creep.
 */
@Parameters
public class BeamCorrectionParameters
        implements Serializable {

    @Parameter(
            names = "--zarrPath",
            description = "Path to the multi-SEM acquisition zarr (xlog) container " +
                          "holding the intensity correction parameters and the scan/slab/sfov coordinate arrays " +
                          "(e.g. /path/to/xlog_wafer_61.zarr)", required = true)
    public String zarrPath;

    @Parameter(
            names = "--homogenizationDataset",
            description = "Name of the 4D correction-parameter array within the zarr container")
    public String homogenizationDataset = "beam_homogenization";

    @Parameter(
            names = "--scanDataset",
            description = "Name of the 1D scan coordinate array")
    public String scanDataset = "scan";

    @Parameter(
            names = "--slabDataset",
            description = "Name of the 1D slab coordinate array (labeled by the magc number of each tile)")
    public String slabDataset = "slab";

    @Parameter(
            names = "--sfovDataset",
            description = "Name of the 1D sfov coordinate array")
    public String sfovDataset = "sfov";

    @Parameter(
            names = "--serialDataset",
            description = "Name of the 1D serial-section coordinate array (only used to cross-check / log the slab selection)")
    public String serialDataset = "id_serial";

    @Parameter(
            names = "--gainIndex",
            description = "Index of the gain parameter within the homogenization_parameter dimension")
    public int gainIndex = 21;

    @Parameter(
            names = "--deg0Index",
            description = "Index of the degree-0 flat-level parameter within the homogenization_parameter dimension")
    public int deg0Index = 22;

    @Parameter(
            names = "--referenceLevel",
            description = "Reference intensity level to map to (defaults to the 'b_ref' attribute of the homogenization array)")
    public Double referenceLevel;

    @Parameter(
            names = "--inverted",
            description = "Indicates that the source images are intensity-inverted " +
                          "(in' = 255 - in) relative to the data the homogenization parameters were computed for. When set, " +
                          "the correction is applied in the original (non-inverted) domain and the result is re-inverted, so " +
                          "the slope stays gain but the offset becomes 255*(1 - gain) - (referenceLevel - gain*degree0).",
            arity = 0)
    public boolean inverted = false;

    @Parameter(
            names = "--sfovLabelOffset",
            description = "Offset added to the sFOV number parsed from the " +
                          "tile id to obtain the xlog sfov coordinate label. Render tile ids are 1-based (s01..s91) while " +
                          "the xlog sfov coordinate is 0-based (0..90), so the default is -1.")
    public int sfovLabelOffset = -1;

    @Parameter(
            names = "--targetStackSuffix",
            description = "Target stack name is the the source stack name with this suffix appended")
    public String targetStackSuffix = "_bc";

    public BeamCorrectionParameters() {
    }

    public void validate()
            throws IllegalArgumentException {

        if ((targetStackSuffix == null) || (targetStackSuffix.trim().isEmpty())) {
            throw new IllegalArgumentException("--targetStackSuffix must be defined");
        }

    }
}
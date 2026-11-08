package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import java.io.Serializable;

import org.janelia.alignment.spec.stack.StackId;

/**
 * Parameters for filling holes in the tissue of exported N5 stack volumes
 * (see org.janelia.render.client.spark.multisem.Wafer6061Inpainter).
 *
 * <p>Each stack's datasets are located with the same naming that the render N5 export uses, e.g. for stack
 * w61_s070_r00_gc_bc_par_cc_aso_3d_s3i in project w61_serial_070_to_079 with the default suffixes:</p>
 * <pre>
 *   pixel:    /render/w61_serial_070_to_079/w61_s070_r00_gc_bc_par_cc_aso_3d_s3i___pixel
 *   mask:     /render/w61_serial_070_to_079/w61_s070_r00_gc_bc_par_cc_aso_3d_s3i___mask/s0
 *   no paint: /render/w61_serial_070_to_079/w61_s070_r00_gc_bc_par_cc_aso_3d_s3i___pixel_no_paint
 * </pre>
 * <p>The no paint dataset holds the original version of every block that inpainting changed.</p>
 */
@Parameters
public class InpaintParameters
        implements Serializable {

    @Parameter(
            names = "--n5Path",
            description = "N5 container holding the exported stack volumes (local path or gs://...), " +
                          "e.g. gs://janelia-spark-test/hess_wafers_60_61_export",
            required = true)
    public String n5Path;

    @Parameter(
            names = "--n5DatasetRoot",
            description = "N5 group that holds the exported project groups")
    public String n5DatasetRoot = "/render";

    @Parameter(
            names = "--pixelDatasetSuffix",
            description = "Suffix appended to the stack name to identify its exported pixel (multiscale) group")
    public String pixelDatasetSuffix = "___pixel";

    @Parameter(
            names = "--maskDatasetSuffix",
            description = "Suffix appended to the stack name to identify its exported mask (multiscale) group")
    public String maskDatasetSuffix = "___mask";

    @Parameter(
            names = "--noPaintDatasetSuffix",
            description = "Suffix appended to the pixel group name to identify the group where the original " +
                          "version of each changed block is saved")
    public String noPaintDatasetSuffix = "_no_paint";

    @Parameter(
            names = "--xlogPath",
            description = "Path to the acquisition xlog zarr (local path or gs://...), " +
                          "e.g. gs://janelia-spark-test/library/xlog_wafer_61.zarr",
            required = true)
    public String xlogPath;

    @Parameter(
            names = "--maxRoiDistance",
            description = "Keep (inpaint) a block only if its interpolated distance_roi (microns) is less than this")
    public double maxRoiDistance = 10.0;

    @Parameter(
            names = "--dryRun",
            description = "Only compute and log candidate / would-be-modified counts; do not write anything",
            arity = 0)
    public boolean dryRun = false;

    public InpaintParameters() {
    }

    /** @return the exported pixel multiscale group for the specified stack. */
    public String getPixelDataset(final StackId stackId) {
        return getProjectGroup(stackId) + "/" + stackId.getStack() + pixelDatasetSuffix;
    }

    /** @return the full scale (s0) dataset of the exported mask for the specified stack. */
    public String getMaskS0Dataset(final StackId stackId) {
        return getProjectGroup(stackId) + "/" + stackId.getStack() + maskDatasetSuffix + "/s0";
    }

    /** @return the multiscale group where the original version of each changed block is saved. */
    public String getNoPaintDataset(final StackId stackId) {
        return getPixelDataset(stackId) + noPaintDatasetSuffix;
    }

    public void validate()
            throws IllegalArgumentException {

        if ((n5Path == null) || n5Path.trim().isEmpty()) {
            throw new IllegalArgumentException("n5Path must be defined");
        }

        if ((xlogPath == null) || xlogPath.trim().isEmpty()) {
            throw new IllegalArgumentException("xlogPath must be defined");
        }

        if ((n5DatasetRoot == null) || (! n5DatasetRoot.startsWith("/"))) {
            throw new IllegalArgumentException("n5DatasetRoot must start with /");
        }

        if ((pixelDatasetSuffix == null) || pixelDatasetSuffix.isEmpty() ||
            (maskDatasetSuffix == null) || maskDatasetSuffix.isEmpty()) {
            throw new IllegalArgumentException("pixelDatasetSuffix and maskDatasetSuffix must be defined");
        }

        if (pixelDatasetSuffix.equals(maskDatasetSuffix)) {
            throw new IllegalArgumentException("pixelDatasetSuffix and maskDatasetSuffix must differ");
        }

        // Validate suffixes: collisions can cause no-paint data to overwrite mask or pixel datasets.
        if (pixelDatasetSuffix.contains("/") || maskDatasetSuffix.contains("/")) {
            throw new IllegalArgumentException("pixelDatasetSuffix and maskDatasetSuffix must not contain /");
        }

        // an empty suffix would save the original blocks over the inpainted ones
        if ((noPaintDatasetSuffix == null) || noPaintDatasetSuffix.isEmpty() || noPaintDatasetSuffix.contains("/")) {
            throw new IllegalArgumentException("noPaintDatasetSuffix must be defined and must not contain /");
        }

        if (maskDatasetSuffix.equals(pixelDatasetSuffix + noPaintDatasetSuffix)) {
            throw new IllegalArgumentException("maskDatasetSuffix must not equal pixelDatasetSuffix + noPaintDatasetSuffix");
        }

        if (! (maxRoiDistance > 0)) {
            throw new IllegalArgumentException("maxRoiDistance must be positive");
        }
    }

    private String getProjectGroup(final StackId stackId) {
        final String root = n5DatasetRoot.endsWith("/") ?
                            n5DatasetRoot.substring(0, n5DatasetRoot.length() - 1) : n5DatasetRoot;
        return root + "/" + stackId.getProject();
    }

    @Override
    public String toString() {
        return "{n5Path='" + n5Path + '\'' +
               ", n5DatasetRoot='" + n5DatasetRoot + '\'' +
               ", pixelDatasetSuffix='" + pixelDatasetSuffix + '\'' +
               ", maskDatasetSuffix='" + maskDatasetSuffix + '\'' +
               ", noPaintDatasetSuffix='" + noPaintDatasetSuffix + '\'' +
               ", xlogPath='" + xlogPath + '\'' +
               ", maxRoiDistance=" + maxRoiDistance +
               ", dryRun=" + dryRun +
               '}';
    }
}

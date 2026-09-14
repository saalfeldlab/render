package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import java.io.Serializable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.janelia.alignment.spec.stack.StackId;

/**
 * Parameters for importing a SOFIMA displacement field into one or more stacks.
 *
 * @see org.janelia.render.client.multisem.ImportSofimaClient
 */
@Parameters
public class SofimaParameters
        implements Serializable {

    @Parameter(
            names = "--sofimaFieldUri",
            description = "URI of the directory containing one SOFIMA displacement field container per slab " +
                          "(e.g. gs://janelia-spark-test/warp_flow_precomputed_260907, which holds " +
                          "w61_s070_r00/info, w61_s085_r01/info, ...)")
    public String sofimaFieldUri;

    @Parameter(
            names = "--scale",
            description = "Full-resolution pixels per field pixel, i.e. the factor by which the field is " +
                          "downsampled in x and y (e.g. 40); derived from the stack bounds and the field " +
                          "dimensions if omitted")
    public Double scale;

    @Parameter(
            names = "--targetStackSuffix",
            description = "Suffix to append to each source stack name to derive its target stack name " +
                          "(e.g. _sofima)")
    public String targetStackSuffix;

    public SofimaParameters() {
        this(null, null, null);
    }

    public SofimaParameters(final String sofimaFieldUri,
                            final Double scale,
                            final String targetStackSuffix) {
        this.sofimaFieldUri = sofimaFieldUri;
        this.scale = scale;
        this.targetStackSuffix = targetStackSuffix;
    }

    public Double getScale() {
        return scale;
    }

    /** @return the target stack id derived from the specified source stack id. */
    public StackId getTargetStackId(final StackId sourceStackId) {
        return sourceStackId.withStackSuffix(targetStackSuffix);
    }

    /**
     * Each slab has its own precomputed field container under {@link #sofimaFieldUri}, named for the slab
     * (e.g. {@code .../warp_flow_precomputed_260907/w61_s070_r00}).  Source stack names start with that
     * same slab name, so the container is derived from the stack name rather than being configured per stack.
     *
     * @param  stack  name of the source stack (e.g. w61_s070_r00_gc_icc_par_asoi_3d).
     *
     * @return the URI of the displacement field container for the specified stack.
     *
     * @throws IllegalArgumentException
     *   if a slab name cannot be derived from the specified stack name.
     */
    public String getFieldUriForStack(final String stack)
            throws IllegalArgumentException {

        final Matcher matcher = SLAB_PATTERN.matcher(stack);
        if (! matcher.find()) {
            throw new IllegalArgumentException(
                    "cannot derive a SOFIMA field container from stack '" + stack +
                    "' because the stack name does not start with a slab name (e.g. w61_s070_r00)");
        }

        // trim any trailing slashes so that a configured uri with or without one works the same way
        return sofimaFieldUri.replaceAll("/+$", "") + "/" + matcher.group(1);
    }

    /**
     * @throws IllegalArgumentException
     *   if these parameters are not sufficient for an import.
     */
    public void validate()
            throws IllegalArgumentException {

        if ((sofimaFieldUri == null) || sofimaFieldUri.trim().isEmpty()) {
            throw new IllegalArgumentException("sofimaFieldUri must be defined");
        }

        // A source stack and its target stack must differ, otherwise the import would add the
        // displacement field to the tile specs it just read and double-transform a rerun.
        if ((targetStackSuffix == null) || targetStackSuffix.trim().isEmpty()) {
            throw new IllegalArgumentException("targetStackSuffix must be defined");
        }
    }

    /** Slab name at the start of a source stack name (e.g. w61_s070_r00 in w61_s070_r00_gc_icc_par_asoi_3d). */
    private static final Pattern SLAB_PATTERN = Pattern.compile("^(w\\d+_s\\d+_r\\d+)");

    @Override
    public String toString() {
        return "{sofimaFieldUri='" + sofimaFieldUri + "', scale=" + scale +
               ", targetStackSuffix='" + targetStackSuffix + "'}";
    }
}

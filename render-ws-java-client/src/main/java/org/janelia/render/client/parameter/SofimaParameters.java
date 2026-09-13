package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import java.io.Serializable;

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
            description = "URI of the SOFIMA displacement field N5 container")
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

    public String getSofimaFieldUri() {
        return sofimaFieldUri;
    }

    public Double getScale() {
        return scale;
    }

    /** @return the target stack id derived from the specified source stack id. */
    public StackId getTargetStackId(final StackId sourceStackId) {
        return sourceStackId.withStackSuffix(targetStackSuffix);
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

    @Override
    public String toString() {
        return "{sofimaFieldUri='" + sofimaFieldUri + "', scale=" + scale +
               ", targetStackSuffix='" + targetStackSuffix + "'}";
    }
}

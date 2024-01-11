package org.janelia.alignment.spec.stack;

import java.io.Serializable;

/**
 * Stack naming groups for common pipeline tasks.
 *
 * @author Eric Trautman
 */
public class PipelineStackIdNamingGroups
        implements Serializable {

    private final StackIdNamingGroup raw;
    private final StackIdNamingGroup aligned;
    private final StackIdNamingGroup intensityCorrected;

    /** No-arg constructor required for JSON deserialization. */
    @SuppressWarnings("unused")
    private PipelineStackIdNamingGroups() {
        this(null, null, null);
    }

    public PipelineStackIdNamingGroups(final StackIdNamingGroup raw,
                                       final StackIdNamingGroup aligned,
                                       final StackIdNamingGroup intensityCorrected) {
        this.raw = raw;
        this.aligned = aligned;
        this.intensityCorrected = intensityCorrected;
    }

    public StackIdNamingGroup getRaw() {
        return raw;
    }

    public StackIdNamingGroup getAligned() {
        return aligned;
    }

    public StackIdNamingGroup getIntensityCorrected() {
        return intensityCorrected;
    }
}

package org.janelia.alignment.spec.stack;

import java.io.Serializable;

/**
 * Stack naming groups for common pipeline tasks.
 *
 * @author Eric Trautman
 */
public record PipelineStackIdNamingGroups(StackIdNamingGroup raw,
                                          StackIdNamingGroup aligned,
                                          StackIdNamingGroup intensityCorrected)
        implements Serializable {

    /**
     * No-arg constructor required for JSON deserialization.
     */
    @SuppressWarnings("unused")
    private PipelineStackIdNamingGroups() {
        this(null, null, null);
    }
}

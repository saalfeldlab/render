package org.janelia.alignment.spec.stack;

import java.io.Serializable;

import org.janelia.alignment.json.JsonUtils;
import org.jspecify.annotations.NonNull;

/**
 * Details about a reconstruction cycle.
 *
 * @author Eric Trautman
 */
public record ReconstructionCycle(Integer number, Integer stepNumber)
        implements Serializable {

    public ReconstructionCycle() {
        this(null, null);
    }

    @Override
    public @NonNull String toString() {
        return toJson();
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    private static final JsonUtils.Helper<ReconstructionCycle> JSON_HELPER =
            new JsonUtils.Helper<>(ReconstructionCycle.class);
}

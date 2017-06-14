package org.janelia.alignment.spec.stack;

import java.io.Serializable;

import org.janelia.alignment.json.JsonUtils;

/**
 * Details about a reconstruction cycle.
 *
 * @author Eric Trautman
 */
public class ReconstructionCycle
        implements Serializable {

    private final Integer number;
    private final Integer stepNumber;

    public ReconstructionCycle() {
        this(null, null);
    }

    public ReconstructionCycle(final Integer number,
                               final Integer stepNumber) {
        this.number = number;
        this.stepNumber = stepNumber;
    }

    public Integer getNumber() {
        return number;
    }

    public Integer getStepNumber() {
        return stepNumber;
    }

    @Override
    public String toString() {
        return toJson();
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    private static final JsonUtils.Helper<ReconstructionCycle> JSON_HELPER =
            new JsonUtils.Helper<>(ReconstructionCycle.class);

}

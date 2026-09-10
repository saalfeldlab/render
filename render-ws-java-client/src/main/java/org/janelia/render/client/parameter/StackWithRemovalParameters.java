package org.janelia.render.client.parameter;

import java.io.Serializable;

import org.janelia.alignment.spec.stack.StackId;

/**
 * Couples a stack with the parameters for removing tiles from that stack.
 */
public class StackWithRemovalParameters
        implements Serializable {

    private final StackId stackId;
    private final MultiSEMTileRemovalParameters tileRemoval;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    public StackWithRemovalParameters() {
        this(null, null);
    }

    public StackWithRemovalParameters(final StackId stackId,
                                      final MultiSEMTileRemovalParameters tileRemoval) {
        this.stackId = stackId;
        this.tileRemoval = tileRemoval;
    }

    public StackId getStackId() {
        return stackId;
    }

    public MultiSEMTileRemovalParameters getTileRemoval() {
        return tileRemoval;
    }

    public void validate()
            throws IllegalArgumentException {

        if (stackId == null) {
            throw new IllegalArgumentException("stackId must be defined for each tile removal element");
        }

        if (tileRemoval == null) {
            throw new IllegalArgumentException("tileRemoval must be defined for " + stackId.toDevString());
        }

        try {
            tileRemoval.validate();
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid tile removal parameters for " + stackId.toDevString() +
                                               ": " + e.getMessage(), e);
        }
    }

    @Override
    public String toString() {
        return "{stackId=" + (stackId == null ? null : stackId.toDevString()) +
               ", tileRemoval=" + tileRemoval +
               '}';
    }
}

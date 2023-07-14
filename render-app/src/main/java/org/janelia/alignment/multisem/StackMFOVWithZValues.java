package org.janelia.alignment.multisem;

import java.io.Serializable;
import java.util.List;

import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackWithZValues;

/**
 * Coupling of an mFOV id and a {@link StackWithZValues}.
 *
 * @author Eric Trautman
 */
public class StackMFOVWithZValues
        implements Serializable {

    private final StackWithZValues stackWithZValues;
    private final String mFOVId;

    public StackMFOVWithZValues(final StackWithZValues stackWithZValues,
                                final String mFOVId) {
        this.stackWithZValues = stackWithZValues;
        this.mFOVId = mFOVId;
    }

    public StackId getStackId() {
        return stackWithZValues.getStackId();
    }

    public List<Double> getzValues() {
        return stackWithZValues.getzValues();
    }

    public String getmFOVId() {
        return mFOVId;
    }

    @Override
    public String toString() {
        return stackWithZValues + "::mfov" + mFOVId;
    }
}

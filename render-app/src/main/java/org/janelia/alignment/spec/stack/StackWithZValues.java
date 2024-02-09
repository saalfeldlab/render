package org.janelia.alignment.spec.stack;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

import io.swagger.annotations.ApiModelProperty;

/**
 * Coupling of a stack id and a list of z values.
 *
 * @author Eric Trautman
 */
public class StackWithZValues implements Serializable {

    private final StackId stackId;
    private final List<Double> zValues;

    public StackWithZValues(final StackId stackId,
                            final List<Double> zValues) {
        this.stackId = stackId;
        this.zValues = zValues;
    }

    public StackWithZValues(final StackId stackId,
                            final Double z) {
        this.stackId = stackId;
        this.zValues = Collections.singletonList(z);
    }

    @ApiModelProperty(value = "stack identifier")
    public StackId getStackId() {
        return stackId;
    }

    @ApiModelProperty(
            name = "zValues",
            value = "list of z values for the stack")
    public List<Double> getzValues() {
        return zValues;
    }

    @Override
    public String toString() {
        return stackId.toDevString() + "::z" + getFirstZ() + "_to_" + getLastZ();
    }

    @JsonIgnore
    public Double getFirstZ() {
        return zValues.isEmpty() ? null : zValues.get(0);
    }

    @JsonIgnore
    public Double getLastZ() {
        return zValues.isEmpty() ? null : zValues.get(zValues.size() - 1);
    }

    public boolean hasSameStack(final StackWithZValues that) {
        return (that != null) && this.stackId.equals(that.stackId);
    }
}

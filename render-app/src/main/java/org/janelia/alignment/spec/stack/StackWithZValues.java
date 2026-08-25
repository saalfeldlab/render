package org.janelia.alignment.spec.stack;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.jspecify.annotations.NonNull;

import io.swagger.annotations.ApiModelProperty;

/**
 * Coupling of a stack id and a list of z values.
 *
 * @author Eric Trautman
 */
public record StackWithZValues(StackId stackId, List<Double> zValues)
        implements Serializable {

    public StackWithZValues(final StackId stackId,
                            final Double z) {
        this(stackId, Collections.singletonList(z));
    }

    @Override
    @ApiModelProperty(value = "stack identifier")
    public StackId stackId() {
        return stackId;
    }

    @Override
    @ApiModelProperty(
            name = "zValues",
            value = "list of z values for the stack")
    public List<Double> zValues() {
        return zValues;
    }

    @Override
    public @NonNull String toString() {
        return stackId.toDevString() + "::z" + getFirstZ() + "_to_" + getLastZ();
    }

    @JsonIgnore
    public Double getFirstZ() {
        return zValues.isEmpty() ? null : zValues.getFirst();
    }

    @JsonIgnore
    public Double getLastZ() {
        return zValues.isEmpty() ? null : zValues.getLast();
    }

    @JsonIgnore
    public int getZCount() {
        return zValues.size();
    }

    public boolean hasSameStack(final StackWithZValues that) {
        return (that != null) && this.stackId.equals(that.stackId);
    }

    /**
     * @return a list of StackWithZValues objects, one for each z value in this object.
     */
    public List<StackWithZValues> splitByZ() {
        if (zValues.size() <= 1) {
            return Collections.singletonList(this);
        }
        return zValues.stream()
                .map(z -> new StackWithZValues(stackId, z))
                .collect(Collectors.toList());
    }
}

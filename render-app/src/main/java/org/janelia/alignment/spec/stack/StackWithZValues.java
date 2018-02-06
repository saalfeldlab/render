package org.janelia.alignment.spec.stack;

import java.util.List;

import io.swagger.annotations.ApiModelProperty;

/**
 * Coupling of a stack id and and list of z values.
 *
 * @author Eric Trautman
 */
public class StackWithZValues {

    private final StackId stackId;
    private final List<Double> zValues;

    public StackWithZValues(final StackId stackId,
                            final List<Double> zValues) {
        this.stackId = stackId;
        this.zValues = zValues;
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
}

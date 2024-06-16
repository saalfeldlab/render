package org.janelia.alignment.spec.transfer;

import com.fasterxml.jackson.annotation.JsonGetter;

/**
 * Render data set information.
 *
 * @author Eric Trautman
 */
public class RenderDataSet {

    private final String owner;
    private final String project;
    private final String stack;
    private final Integer maskWidth;
    private final Integer maskHeight;
    private final Connect connect;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private RenderDataSet() {
        this(null, null, null, null, null, null);
    }

    public RenderDataSet(final String owner,
                         final String project,
                         final String stack,
                         final Integer maskWidth,
                         final Integer maskHeight,
                         final Connect connect) {
        this.owner = owner;
        this.project = project;
        this.stack = stack;
        this.maskWidth = maskWidth;
        this.maskHeight = maskHeight;
        this.connect = connect;
    }

    public String getOwner() {
        return owner;
    }

    public String getProject() {
        return project;
    }

    public String getStack() {
        return stack;
    }

    @JsonGetter(value = "mask_width")
    public Integer getMaskWidth() {
        return maskWidth;
    }

    @JsonGetter(value = "mask_height")
    public Integer getMaskHeight() {
        return maskHeight;
    }

    public Connect getConnect() {
        return connect;
    }
}

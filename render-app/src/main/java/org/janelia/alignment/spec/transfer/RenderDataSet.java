package org.janelia.alignment.spec.transfer;

import com.fasterxml.jackson.annotation.JsonGetter;

import java.io.Serializable;

/**
 * Render data set information.
 *
 * @author Eric Trautman
 */
public record RenderDataSet(String owner,
                            String project,
                            String stack,
                            @JsonGetter(value = "mask_width") Integer maskWidth,
                            @JsonGetter(value = "mask_height") Integer maskHeight,
                            Connect connect)
        implements Serializable {

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private RenderDataSet() {
        this(null, null, null, null, null, null);
    }
}

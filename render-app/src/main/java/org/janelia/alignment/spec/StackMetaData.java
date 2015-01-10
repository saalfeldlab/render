package org.janelia.alignment.spec;

import java.io.Serializable;

import org.janelia.alignment.json.JsonUtils;

/**
 * Meta data about a stack.
 *
 * @author Eric Trautman
 */
public class StackMetaData implements Serializable {

    private final Integer layoutWidth;
    private final Integer layoutHeight;

    public StackMetaData() {
        this(null, null);
    }

    public StackMetaData(final Integer layoutWidth,
                         final Integer layoutHeight) {
        this.layoutWidth = layoutWidth;
        this.layoutHeight = layoutHeight;
    }

    public Integer getLayoutWidth() {
        return layoutWidth;
    }

    public Integer getLayoutHeight() {
        return layoutHeight;
    }

    public static StackMetaData fromJson(final String json) {
        return JsonUtils.GSON.fromJson(json, StackMetaData.class);
    }
}

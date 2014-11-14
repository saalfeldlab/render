package org.janelia.alignment.spec;

import org.janelia.alignment.json.JsonUtils;

/**
 * Meta data about a stack.
 *
 * @author Eric Trautman
 */
public class StackMetaData {

    private Integer layoutWidth;
    private Integer layoutHeight;

    public StackMetaData() {
        this(null, null);
    }

    public StackMetaData(Integer layoutWidth,
                         Integer layoutHeight) {
        this.layoutWidth = layoutWidth;
        this.layoutHeight = layoutHeight;
    }

    public Integer getLayoutWidth() {
        return layoutWidth;
    }

    public Integer getLayoutHeight() {
        return layoutHeight;
    }

    public static StackMetaData fromJson(String json) {
        return JsonUtils.GSON.fromJson(json, StackMetaData.class);
    }
}

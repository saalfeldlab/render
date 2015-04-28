package org.janelia.render.service.model.stack;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;

/**
 * Meta data about a stack and all of its versions.
 *
 * @author Eric Trautman
 */
public class StackMetaData implements Serializable {

    public enum StackState { LOADING, COMPLETE, OFFLINE }

    private final StackId stackId;
    private final StackContext stackContext;
    private final StackState state;
    private final Date lastModifiedDate;
    private final StackVersion currentVersion;
    private final List<StackVersion> historicalVersions;

    public StackMetaData() {
        this(null, null, null, null, null, null);
    }

    public StackMetaData(StackId stackId,
                         StackContext stackContext,
                         StackState state,
                         Date lastModifiedDate,
                         StackVersion currentVersion,
                         List<StackVersion> historicalVersions) {
        this.stackId = stackId;
        this.stackContext = stackContext;
        this.state = state;
        this.lastModifiedDate = lastModifiedDate;
        this.currentVersion = currentVersion;
        this.historicalVersions = historicalVersions;
    }

    public StackId getStackId() {
        return stackId;
    }

    public StackContext getStackContext() {
        return stackContext;
    }

    public StackState getState() {
        return state;
    }

    public Date getLastModifiedDate() {
        return lastModifiedDate;
    }

    public StackVersion getCurrentVersion() {
        return currentVersion;
    }

    public Integer getLayoutWidth() {
        Integer layoutWidth = null;
        if (currentVersion != null) {
            final StackStats stats = currentVersion.getStats();
            if (stats != null) {
                layoutWidth = stats.getMaxTileWidth();
            }
        }
        return layoutWidth;
    }

    public Integer getLayoutHeight() {
        Integer layoutHeight = null;
        if (currentVersion != null) {
            final StackStats stats = currentVersion.getStats();
            if (stats != null) {
                layoutHeight = stats.getMaxTileHeight();
            }
        }
        return layoutHeight;
    }

    public List<StackVersion> getHistoricalVersions() {
        return historicalVersions;
    }

    public static StackMetaData fromJson(final String json) {
        return JsonUtils.GSON.fromJson(json, StackMetaData.class);
    }
}

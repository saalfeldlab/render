package org.janelia.alignment.spec.stack;

import java.io.Serializable;
import java.util.Date;

import org.janelia.alignment.json.JsonUtils;

import static org.janelia.alignment.spec.stack.StackMetaData.StackState.*;

/**
 * Meta data about a stack.
 *
 * @author Eric Trautman
 */
public class StackMetaData implements Comparable<StackMetaData>, Serializable {

    public enum StackState { LOADING, COMPLETE, OFFLINE }

    private final StackId stackId;

    private StackState state;
    private Date lastModifiedTimestamp;
    private Integer currentVersionNumber;
    private final StackVersion currentVersion;
    private StackStats stats;

    public StackMetaData(final StackId stackId,
                         final StackVersion currentVersion) {
        this.stackId = stackId;
        this.state = LOADING;
        this.lastModifiedTimestamp = new Date();
        this.currentVersionNumber = 0;
        this.currentVersion = currentVersion;
        this.stats = null;
    }

    public StackId getStackId() {
        return stackId;
    }

    public StackState getState() {
        return state;
    }

    public boolean isLoading() {
        return LOADING.equals(state);
    }

    public Date getLastModifiedTimestamp() {
        return lastModifiedTimestamp;
    }

    public Integer getCurrentVersionNumber() {
        return currentVersionNumber;
    }

    public StackVersion getCurrentVersion() {
        return currentVersion;
    }

    public StackStats getStats() {
        return stats;
    }

    public Integer getLayoutWidth() {
        Integer layoutWidth = null;
        if (stats != null) {
            layoutWidth = stats.getMaxTileWidth();
        }
        return layoutWidth;
    }

    public Integer getLayoutHeight() {
        Integer layoutHeight = null;
        if (stats != null) {
            layoutHeight = stats.getMaxTileHeight();
        }
        return layoutHeight;
    }

    public StackMetaData getNextVersion(final StackVersion newVersion) {
        final StackMetaData metaData = new StackMetaData(stackId, newVersion);
        metaData.currentVersionNumber = currentVersionNumber + 1;
        return metaData;
    }

    public void setState(final StackState state) throws IllegalArgumentException {

        if (state == null) {
            throw new IllegalArgumentException("null state specified");
        }

        if (! this.state.equals(state)) {

            if (COMPLETE.equals(state)) {
                if (this.stats == null) {
                    throw new IllegalArgumentException("stack can not be complete without stats");
                }
            } else if (LOADING.equals(state)) {
                this.stats = null;
                this.currentVersionNumber++;
            }

            this.state = state;
            this.lastModifiedTimestamp = new Date();

        }

    }

    public void setStats(final StackStats stats) throws IllegalArgumentException {

        if (OFFLINE.equals(state)) {
            throw new IllegalArgumentException("cannot set stats for OFFLINE stack");
        }

        this.stats = stats;

        if (stats == null) {
            setState(StackState.LOADING);
        } else {
            setState(StackState.COMPLETE);
        }
    }

    public MipmapPathBuilder getCurrentMipmapPathBuilder() {
        MipmapPathBuilder mipmapPathBuilder = null;
        if (currentVersion != null) {
            mipmapPathBuilder = currentVersion.getMipmapPathBuilder();
        }
        return mipmapPathBuilder;
    }

    @SuppressWarnings("NullableProblems")
    @Override
    public int compareTo(final StackMetaData that) {
        return this.stackId.compareTo(that.stackId);
    }

    @Override
    public String toString() {
        return toJson();
    }

    public String toJson() {
        return JsonUtils.GSON.toJson(this, StackMetaData.class);
    }

    public static StackMetaData fromJson(final String json) {
        return JsonUtils.GSON.fromJson(json, StackMetaData.class);
    }
}

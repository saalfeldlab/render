package org.janelia.alignment.spec.stack;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;

import static org.janelia.alignment.spec.stack.StackMetaData.StackState.*;

/**
 * Meta data about a stack.
 *
 * @author Eric Trautman
 */
public class StackMetaData implements Comparable<StackMetaData>, Serializable {

    public enum StackState { LOADING, COMPLETE, READ_ONLY, OFFLINE }

    private final StackId stackId;

    private StackState state;
    private Date lastModifiedTimestamp;
    private Integer currentVersionNumber;
    private final StackVersion currentVersion;
    private StackStats stats;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private StackMetaData() {
        this.stackId = null;
        this.state = LOADING;
        this.lastModifiedTimestamp = null;
        this.currentVersionNumber = null;
        this.currentVersion = null;
        this.stats = null;
    }

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

    public boolean isReadOnly() {
        return READ_ONLY.equals(state);
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

    /**
     * Validates the specified stack state change.
     *
     * Permitted state transitions are:
     * <pre>
     *         LOADING -> COMPLETE
     *
     *         COMPLETE -> LOADING
     *         COMPLETE -> READ_ONLY
     *         COMPLETE -> OFFLINE
     *
     *         READ_ONLY -> COMPLETE
     *         READ_ONLY -> OFFLINE
     *
     *         OFFLINE -> COMPLETE
     * </pre>
     *
     * @param  toState  potential new state for this stack.
     *
     * @throws IllegalArgumentException
     *   if the state change is invalid.
     */
    public void validateStateChange(final StackState toState)
            throws IllegalArgumentException {

        if (toState == null) {

            throw new IllegalArgumentException("null state specified");

        } else if (! state.equals(toState)) {

            if (LOADING.equals(state) || OFFLINE.equals(state)) {

                if (! COMPLETE.equals(toState)) {
                    throwStackMustBeCompleteException(toState);
                }

            } else if (READ_ONLY.equals(state)) {

                if (! (COMPLETE.equals(toState) || OFFLINE.equals(toState))) {
                    throwStackMustBeCompleteException(toState);
                }

            } // else current state is COMPLETE so all transitions are allowed
        }

    }

    private String throwStackMustBeCompleteException(final StackState toState) throws IllegalArgumentException {
        final String stackName = stackId == null ? "" : stackId.getStack() + " ";
        throw new IllegalArgumentException(
                "The " + stackName + "stack's state is currently " + state +
                " and must first be transitioned to " + COMPLETE +
                " before transitioning it to " + toState + ".");
    }

    public void setState(final StackState toState) throws IllegalArgumentException {

        final StackState fromState = this.state;

        validateStateChange(toState);

        if (! fromState.equals(toState)) {

            boolean updateModifiedTimestamp = true; // update timestamp for most state transitions

            if (COMPLETE.equals(toState)) {

                if (this.stats == null) {
                    throw new IllegalArgumentException("stack can not be complete without stats");
                }

                if (READ_ONLY.equals(fromState)) {
                    updateModifiedTimestamp = false; // do not change timestamp when going to COMPLETE from READ_ONLY
                }

            } else if (LOADING.equals(toState)) {

                this.stats = null;
                this.currentVersionNumber++;

            } else if (READ_ONLY.equals(toState)) {

                if (COMPLETE.equals(fromState)) {
                    updateModifiedTimestamp = false; // do not change timestamp when going to READ_ONLY from COMPLETE
                }

            }

            this.state = toState;

            if (updateModifiedTimestamp) {
                this.lastModifiedTimestamp = new Date();
            }

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

    public List<Double> getCurrentResolutionValues() {
        final List<Double> resolutionValues;
        if (currentVersion != null) {
            resolutionValues = currentVersion.getStackResolutionValues();
        } else {
            resolutionValues = new ArrayList<>();
        }
        return resolutionValues;
    }

    public void setCurrentResolutionValues(final List<Double> resolutionValues) {
        if (currentVersion != null) {
            currentVersion.setStackResolutionValues(resolutionValues);
        }
    }

    public String getCurrentMaterializedBoxRootPath() {
        String path = null;
        if (currentVersion != null) {
            path = currentVersion.getMaterializedBoxRootPath();
        }
        return path;
    }

    public void setCurrentMaterializedBoxRootPath(final String materializedBoxRootPath) {
        if (currentVersion != null) {
            currentVersion.setMaterializedBoxRootPath(materializedBoxRootPath);
        }
    }

    public MipmapPathBuilder getCurrentMipmapPathBuilder() {
        MipmapPathBuilder mipmapPathBuilder = null;
        if (currentVersion != null) {
            mipmapPathBuilder = currentVersion.getMipmapPathBuilder();
        }
        return mipmapPathBuilder;
    }

    public void setCurrentMipmapPathBuilder(final MipmapPathBuilder mipmapPathBuilder)
            throws IllegalArgumentException {

        if (currentVersion != null) {
            currentVersion.setMipmapPathBuilder(mipmapPathBuilder);
        }
    }

    @SuppressWarnings({"ConstantConditions", "NullableProblems"})
    @Override
    public int compareTo(final StackMetaData that) {
        return this.stackId.compareTo(that.stackId);
    }

    @Override
    public String toString() {
        return toJson();
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    public static StackMetaData fromJson(final String json) {
        return JSON_HELPER.fromJson(json);
    }

    private static final JsonUtils.Helper<StackMetaData> JSON_HELPER =
            new JsonUtils.Helper<>(StackMetaData.class);
}

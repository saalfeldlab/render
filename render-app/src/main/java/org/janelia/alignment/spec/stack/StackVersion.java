package org.janelia.alignment.spec.stack;

import java.io.Serializable;
import java.util.Date;

import org.janelia.alignment.json.JsonUtils;

/**
 * Details about a specific version of stack.
 *
 * @author Eric Trautman
 */
public class StackVersion
        implements Serializable {

    private final Date createTimestamp;
    private final String versionNotes;

    private final Integer cycleNumber;
    private final Integer cycleStepNumber;

    private final Double stackResolutionX;
    private final Double stackResolutionY;
    private final Double stackResolutionZ;

    private final String snapshotRootPath;
    private final MipmapPathBuilder mipmapPathBuilder;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private StackVersion() {
        this.createTimestamp = null;
        this.versionNotes = null;
        this.cycleNumber = null;
        this.cycleStepNumber = null;
        this.stackResolutionX = null;
        this.stackResolutionY = null;
        this.stackResolutionZ = null;
        this.snapshotRootPath = null;
        this.mipmapPathBuilder = null;
    }

    public StackVersion(final Date createTimestamp,
                        final String versionNotes,
                        final Integer cycleNumber,
                        final Integer cycleStepNumber,
                        final Double stackResolutionX,
                        final Double stackResolutionY,
                        final Double stackResolutionZ,
                        final String snapshotRootPath,
                        final MipmapPathBuilder mipmapPathBuilder) {
        this.createTimestamp = createTimestamp;
        this.versionNotes = versionNotes;
        this.cycleNumber = cycleNumber;
        this.cycleStepNumber = cycleStepNumber;
        this.stackResolutionX = stackResolutionX;
        this.stackResolutionY = stackResolutionY;
        this.stackResolutionZ = stackResolutionZ;
        this.snapshotRootPath = snapshotRootPath;
        this.mipmapPathBuilder = mipmapPathBuilder;
    }

    public Date getCreateTimestamp() {
        return createTimestamp;
    }

    public String getVersionNotes() {
        return versionNotes;
    }

    public Integer getCycleNumber() {
        return cycleNumber;
    }

    public Integer getCycleStepNumber() {
        return cycleStepNumber;
    }

    public Double getStackResolutionX() {
        return stackResolutionX;
    }

    public Double getStackResolutionY() {
        return stackResolutionY;
    }

    public Double getStackResolutionZ() {
        return stackResolutionZ;
    }

    public String getSnapshotRootPath() {
        return snapshotRootPath;
    }

    public boolean isSnapshotNeeded() {
        return snapshotRootPath != null;
    }

    public MipmapPathBuilder getMipmapPathBuilder() {
        return mipmapPathBuilder;
    }

    @Override
    public String toString() {
        return toJson();
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    private static final JsonUtils.Helper<StackVersion> JSON_HELPER =
            new JsonUtils.Helper<>(StackVersion.class);
}

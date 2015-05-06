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
    private final MipmapMetaData mipmapMetaData;

    public StackVersion(Date createTimestamp,
                        String versionNotes,
                        Integer cycleNumber,
                        Integer cycleStepNumber,
                        Double stackResolutionX,
                        Double stackResolutionY,
                        Double stackResolutionZ,
                        String snapshotRootPath,
                        MipmapMetaData mipmapMetaData) {
        this.createTimestamp = createTimestamp;
        this.versionNotes = versionNotes;
        this.cycleNumber = cycleNumber;
        this.cycleStepNumber = cycleStepNumber;
        this.stackResolutionX = stackResolutionX;
        this.stackResolutionY = stackResolutionY;
        this.stackResolutionZ = stackResolutionZ;
        this.snapshotRootPath = snapshotRootPath;
        this.mipmapMetaData = mipmapMetaData;
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

    public MipmapMetaData getMipmapMetaData() {
        return mipmapMetaData;
    }

    @Override
    public String toString() {
        return toJson();
    }

    public String toJson() {
        return JsonUtils.GSON.toJson(this, StackVersion.class);
    }
}

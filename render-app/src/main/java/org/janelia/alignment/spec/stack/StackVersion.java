package org.janelia.alignment.spec.stack;

import java.io.Serializable;
import java.util.Date;

/**
 * Details about a specific version of stack.
 *
 * @author Eric Trautman
 */
public class StackVersion
        implements Serializable {

    private final Date createTimestamp;
    private final String versionNotes;

    private final Integer projectIteration;
    private final Integer projectStep;

    private final Double stackResolutionX;
    private final Double stackResolutionY;
    private final Double stackResolutionZ;

    private final String snapshotRootPath;
    private final MipmapMetaData mipmapMetaData;

    public StackVersion(Date createTimestamp,
                        String versionNotes,
                        Integer projectIteration,
                        Integer projectStep,
                        Double stackResolutionX,
                        Double stackResolutionY,
                        Double stackResolutionZ,
                        String snapshotRootPath,
                        MipmapMetaData mipmapMetaData) {
        this.createTimestamp = createTimestamp;
        this.versionNotes = versionNotes;
        this.projectIteration = projectIteration;
        this.projectStep = projectStep;
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

    public Integer getProjectIteration() {
        return projectIteration;
    }

    public Integer getProjectStep() {
        return projectStep;
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
        return "StackVersion{" +
               "createTimestamp=" + createTimestamp +
               ", versionNotes='" + versionNotes + '\'' +
               ", projectIteration=" + projectIteration +
               ", projectStep=" + projectStep +
               ", stackResolutionX=" + stackResolutionX +
               ", stackResolutionY=" + stackResolutionY +
               ", stackResolutionZ=" + stackResolutionZ +
               ", snapshotRootPath='" + snapshotRootPath + '\'' +
               ", mipmapMetaData=" + mipmapMetaData +
               '}';
    }
}

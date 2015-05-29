package org.janelia.render.service.model;

import java.io.Serializable;
import java.util.Date;

import org.janelia.alignment.json.JsonUtils;

/**
 * Archival details about a collection snapshot.
 *
 * @author Eric Trautman
 */
public class CollectionSnapshot
        implements Serializable {

    private final String owner;
    private final String project;
    private final String databaseName;
    private final String collectionName;
    private final Integer version;
    private final String rootPath;
    private final Date collectionCreateTimestamp;
    private final String versionNotes;
    private final Long estimatedBytes;
    private final Date snapshotDate;
    private final String fullPath;
    private final Long actualBytes;

    public CollectionSnapshot(String owner,
                              String project,
                              String databaseName,
                              String collectionName,
                              Integer version,
                              String rootPath,
                              Date collectionCreateTimestamp,
                              String versionNotes,
                              Long estimatedBytes) throws IllegalArgumentException {
        this(owner,
             project,
             databaseName,
             collectionName,
             version,
             rootPath,
             collectionCreateTimestamp,
             versionNotes,
             estimatedBytes,
             null,
             null,
             null);
    }

    public CollectionSnapshot(String owner,
                              String project,
                              String databaseName,
                              String collectionName,
                              Integer version,
                              String rootPath,
                              Date collectionCreateTimestamp,
                              String versionNotes,
                              Long estimatedBytes,
                              Date snapshotDate,
                              String fullPath,
                              Long actualBytes) throws IllegalArgumentException {
        this.owner = owner;
        this.project = project;
        this.databaseName = databaseName;
        this.collectionName = collectionName;
        this.version = version;
        this.rootPath = rootPath;
        this.collectionCreateTimestamp = collectionCreateTimestamp;
        this.versionNotes = versionNotes;
        this.estimatedBytes = estimatedBytes;
        this.snapshotDate = snapshotDate;
        this.fullPath = fullPath;
        this.actualBytes = actualBytes;

        validate();
    }

    public CollectionSnapshot getSnapshotWithPersistenceData(Date snapshotDate,
                                                             String fullPath,
                                                             Long actualBytes) {
        return new CollectionSnapshot(this.owner,
                                      this.project,
                                      this.databaseName,
                                      this.collectionName,
                                      this.version,
                                      this.rootPath,
                                      this.collectionCreateTimestamp,
                                      this.versionNotes,
                                      this.estimatedBytes,
                                      snapshotDate,
                                      fullPath,
                                      actualBytes);
    }

    public void validate() throws IllegalArgumentException {
        if (owner == null) {
            throw new IllegalArgumentException("snapshot must include an owner");
        }
        if (databaseName == null) {
            throw new IllegalArgumentException("snapshot must include a databaseName");
        }
        if (collectionName == null) {
            throw new IllegalArgumentException("snapshot must include a collectionName");
        }
        if (version == null) {
            throw new IllegalArgumentException("snapshot must include s version");
        }
        if (rootPath == null) {
            throw new IllegalArgumentException("snapshot must include a rootPath");
        }
    }

    public String getOwner() {
        return owner;
    }

    public String getProject() {
        return project;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public String getCollectionName() {
        return collectionName;
    }

    public Integer getVersion() {
        return version;
    }

    public String getRootPath() {
        return rootPath;
    }

    public Date getCollectionCreateTimestamp() {
        return collectionCreateTimestamp;
    }

    public String getVersionNotes() {
        return versionNotes;
    }

    public Long getEstimatedBytes() {
        return estimatedBytes;
    }

    public Date getSnapshotDate() {
        return snapshotDate;
    }

    public boolean isSaved() {
        return (snapshotDate != null);
    }

    public String getFullPath() {
        return fullPath;
    }

    public Long getActualBytes() {
        return actualBytes;
    }

    @Override
    public String toString() {
        return toJson();
    }

    public String toJson() {
        return JsonUtils.GSON.toJson(this, CollectionSnapshot.class);
    }

    public static CollectionSnapshot fromJson(final String json) {
        return JsonUtils.GSON.fromJson(json, CollectionSnapshot.class);
    }

}

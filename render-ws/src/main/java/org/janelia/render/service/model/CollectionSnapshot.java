package org.janelia.render.service.model;

import java.io.Serializable;
import java.util.Date;

/**
 * Archival details about a collection snapshot.
 *
 * @author Eric Trautman
 */
public class CollectionSnapshot
        implements Serializable {

    private final String databaseName;
    private final String collectionName;
    private final Integer version;
    private final Date createDate;
    private final String archiveRootPath;
    private final Date archiveDate;
    private final String archivePath;

    public CollectionSnapshot(String databaseName,
                              String collectionName,
                              Integer version,
                              Date createDate,
                              String archiveRootPath,
                              Date archiveDate,
                              String archivePath) {
        this.databaseName = databaseName;
        this.collectionName = collectionName;
        this.version = version;
        this.createDate = createDate;
        this.archiveRootPath = archiveRootPath;
        this.archiveDate = archiveDate;
        this.archivePath = archivePath;
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

    public Date getCreateDate() {
        return createDate;
    }

    public String getArchiveRootPath() {
        return archiveRootPath;
    }

    public Date getArchiveDate() {
        return archiveDate;
    }

    public String getArchivePath() {
        return archivePath;
    }
}

package org.janelia.render.service.dao;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.mongodb.WriteResult;
import com.mongodb.util.JSON;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import org.janelia.render.service.model.CollectionSnapshot;
import org.janelia.render.service.model.ObjectNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Data access object for Admin database.
 *
 * @author Eric Trautman
 */
public class AdminDao {

    public static final String ADMIN_DB_NAME = "admin";
    public static final String SNAPSHOT_COLLECTION_NAME = "snapshot";

    private final DB renderDb;

    public static AdminDao build()
            throws UnknownHostException {
        final MongoClient mongoClient = SharedMongoClient.getInstance();
        return new AdminDao(mongoClient);
    }

    public AdminDao(final MongoClient client) {
        renderDb = client.getDB(ADMIN_DB_NAME);
    }

    /**
     * @return a list of snapshots that match the specified criteria.
     */
    public List<CollectionSnapshot> getSnapshots(final String owner,
                                                 final String databaseName,
                                                 final String collectionName,
                                                 final Boolean filterOutPersistedSnapshots) {

        final List<CollectionSnapshot> snapshotList = new ArrayList<>();

        final BasicDBObject query = new BasicDBObject();

        if (owner != null) {
            query.append("owner", owner);
        }

        if (databaseName != null) {
            query.append("databaseName", databaseName);
        }

        if (collectionName != null) {
            query.append("collectionName", collectionName);
        }

        if ((filterOutPersistedSnapshots != null) && (filterOutPersistedSnapshots)) {
            query.append("snapshotDate", null);
        }

        final BasicDBObject sortQuery = new BasicDBObject(
                "owner", 1).append(
                "databaseName", 1).append(
                "collectionName", 1).append(
                "version", 1);

        final DBCollection snapshotCollection = getSnapshotCollection();

        try (DBCursor cursor = snapshotCollection.find(query)) {

            cursor.sort(sortQuery);

            DBObject document;
            CollectionSnapshot snapshot;
            while (cursor.hasNext()) {
                document = cursor.next();
                snapshot = CollectionSnapshot.fromJson(document.toString());
                snapshotList.add(snapshot);
            }
        }

        LOG.debug("getSnapshots: returning {} snapshot(s) for {}.find({}).sort({})",
                  snapshotList.size(), snapshotCollection.getFullName(), query, sortQuery);

        return snapshotList;
    }

    /**
     * @return the specified snapshot.
     *
     * @throws IllegalArgumentException
     *   if any required parameters are missing.
     *
     * @throws ObjectNotFoundException
     *   if a matching snapshot cannot be found.
     */
    public CollectionSnapshot getSnapshot(final String owner,
                                          final String databaseName,
                                          final String collectionName,
                                          final Integer version)
            throws IllegalArgumentException,
                   ObjectNotFoundException {

        validateRequiredParameter("owner", owner);
        validateRequiredParameter("databaseName", databaseName);
        validateRequiredParameter("collectionName", collectionName);
        validateRequiredParameter("version", version);

        final DBCollection snapshotCollection = getSnapshotCollection();

        final BasicDBObject query = getSnapshotVersionQuery(owner, databaseName, collectionName, version);

        LOG.debug("getSnapshot: {}.find({})",
                  snapshotCollection.getFullName(), query);

        final DBObject document = snapshotCollection.findOne(query);

        if (document == null) {
            throw new ObjectNotFoundException("snapshot with owner '" + owner +
                                              "', databaseName '" + databaseName +
                                              "', collectionName '" + collectionName +
                                              "', and version '" + version +
                                              " ' does not exist");
        }

        return CollectionSnapshot.fromJson(document.toString());
    }

     /**
     * Saves the specified snapshot data to the database.
     *
     * @param  snapshot   snapshot data.
     *
     * @throws IllegalArgumentException
     *   if any required parameters are missing.
     */
    public void saveSnapshot(final CollectionSnapshot snapshot)
            throws IllegalArgumentException {

        validateRequiredParameter("snapshot", snapshot);

        snapshot.validate();

        final DBCollection snapshotCollection = getSnapshotCollection();

        ensureSnapshotIndexes(snapshotCollection);

        final BasicDBObject query = getSnapshotVersionQuery(snapshot.getOwner(),
                                                            snapshot.getDatabaseName(),
                                                            snapshot.getCollectionName(),
                                                            snapshot.getVersion());

        final DBObject snapshotObject = (DBObject) JSON.parse(snapshot.toJson());

        final WriteResult result = snapshotCollection.update(query, snapshotObject, true, false);

        String action;
        if (result.isUpdateOfExisting()) {
            action = "update";
        } else {
            action = "insert";
        }

        LOG.debug("saveSnapshot: exit, executed {}.{}({})",
                  snapshotCollection.getFullName(), action, query);
    }

    public void removeSnapshot(final String owner,
                               final String databaseName,
                               final String collectionName,
                               final Integer version)
            throws IllegalArgumentException,
                   ObjectNotFoundException {

        validateRequiredParameter("owner", owner);
        validateRequiredParameter("databaseName", databaseName);
        validateRequiredParameter("collectionName", collectionName);
        validateRequiredParameter("version", version);

        final DBCollection snapshotCollection = getSnapshotCollection();

        final BasicDBObject query = getSnapshotVersionQuery(owner, databaseName, collectionName, version);

        final WriteResult removeResult = snapshotCollection.remove(query);

        LOG.debug("removeSnapshot: {}.remove({}) deleted {} document(s)",
                  snapshotCollection.getFullName(), query, removeResult.getN());
    }

    private DBCollection getSnapshotCollection() {
        return renderDb.getCollection(SNAPSHOT_COLLECTION_NAME);
    }

    private void validateRequiredParameter(final String context,
                                           final Object value)
            throws IllegalArgumentException {

        if (value == null) {
            throw new IllegalArgumentException(context + " value must be specified");
        }
    }

    private void ensureSnapshotIndexes(final DBCollection snapshotCollection) {

        // primary key index
        ensureIndex(snapshotCollection,
                    new BasicDBObject(
                            "databaseName", 1).append(
                            "collectionName", 1).append(
                            "version", 1),
                    new BasicDBObject(
                            "unique", true).append(
                            "background", true));

        // sorting index
        ensureIndex(snapshotCollection,
                    new BasicDBObject(
                            "owner", 1).append(
                            "databaseName", 1).append(
                            "collectionName", 1).append(
                            "version", 1),
                    new BasicDBObject(
                            "background", true));

        LOG.debug("ensureSnapshotIndexes: exit");
    }

    private void ensureIndex(final DBCollection collection,
                             final DBObject keys,
                             final DBObject options) {
        LOG.debug("ensureIndex: entry, collection={}, keys={}, options={}", collection.getName(), keys, options);
        collection.createIndex(keys, options);
    }

    private BasicDBObject getSnapshotVersionQuery(final String owner,
                                                  final String databaseName,
                                                  final String collectionName,
                                                  final Integer version) {
        return new BasicDBObject(
                "owner", owner).append(
                "databaseName", databaseName).append(
                "collectionName", collectionName).append(
                "version", version);
    }

    private static final Logger LOG = LoggerFactory.getLogger(AdminDao.class);
}

package org.janelia.render.service.dao;

import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.BulkWriteOperation;
import com.mongodb.BulkWriteResult;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.mongodb.QueryOperators;
import com.mongodb.util.JSON;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.util.ProcessTimer;
import org.janelia.render.service.model.ObjectNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Data access object for Match database.
 *
 * @author Eric Trautman
 */
public class MatchDao {

    public static final String MATCH_DB_NAME = "match";

    private final DB matchDb;

    public MatchDao(final MongoClient client) {
        matchDb = client.getDB(MATCH_DB_NAME);
    }

    public void writeMatchesWithinGroup(final MatchCollectionId collectionId,
                                        final String groupId,
                                        final OutputStream outputStream)
            throws IllegalArgumentException, IOException, ObjectNotFoundException {

        LOG.debug("writeMatchesWithinGroup: entry, collectionId={}, groupId={}",
                  collectionId, groupId);

        validateRequiredParameter("collectionId", collectionId);
        validateRequiredParameter("groupId", groupId);

        final DBCollection collection = getExistingCollection(collectionId.getDbCollectionName());
        final BasicDBObject query = new BasicDBObject("pGroupId", groupId).append("qGroupId", groupId);

        writeMatches(collection, query, outputStream);
    }

    public void writeMatchesOutsideGroup(final MatchCollectionId collectionId,
                                         final String groupId,
                                         final OutputStream outputStream)
            throws IllegalArgumentException, IOException, ObjectNotFoundException {

        LOG.debug("writeMatchesOutsideGroup: entry, collectionId={}, groupId={}",
                  collectionId, groupId);

        validateRequiredParameter("collectionId", collectionId);
        validateRequiredParameter("groupId", groupId);

        final DBCollection collection = getExistingCollection(collectionId.getDbCollectionName());
        final BasicDBObject query = getOutsideGroupQuery(groupId);

        writeMatches(collection, query, outputStream);
    }

    public void writeMatchesBetweenGroups(final MatchCollectionId collectionId,
                                          final String pGroupId,
                                          final String qGroupId,
                                          final OutputStream outputStream)
            throws IllegalArgumentException, IOException, ObjectNotFoundException {

        LOG.debug("writeMatchesBetweenGroups: entry, collectionId={}, pGroupId={}, pGroupId={}",
                  collectionId, pGroupId, qGroupId);

        validateRequiredParameter("collectionId", collectionId);
        validateRequiredParameter("pGroupId", pGroupId);
        validateRequiredParameter("qGroupId", qGroupId);

        final DBCollection collection = getExistingCollection(collectionId.getDbCollectionName());
        final String noTileId = "";
        final CanvasMatches normalizedCriteria = new CanvasMatches(pGroupId, noTileId, qGroupId, noTileId, null);
        final BasicDBObject query = new BasicDBObject(
                "pGroupId", normalizedCriteria.getpGroupId()).append(
                "qGroupId", normalizedCriteria.getqGroupId());

        writeMatches(collection, query, outputStream);
    }

    public void writeMatchesBetweenObjects(final MatchCollectionId collectionId,
                                           final String pGroupId,
                                           final String pId,
                                           final String qGroupId,
                                           final String qId,
                                           final OutputStream outputStream)
            throws IllegalArgumentException, IOException, ObjectNotFoundException {

        LOG.debug("writeMatchesBetweenObjects: entry, collectionId={}, pGroupId={}, pId={}, qGroupId={}, qId={}",
                  collectionId, pGroupId, pId, qGroupId, qId);

        validateRequiredParameter("collectionId", collectionId);
        validateRequiredParameter("pGroupId", pGroupId);
        validateRequiredParameter("pId", pId);
        validateRequiredParameter("qGroupId", qGroupId);
        validateRequiredParameter("qId", qId);

        final DBCollection collection = getExistingCollection(collectionId.getDbCollectionName());
        final CanvasMatches normalizedCriteria = new CanvasMatches(pGroupId, pId, qGroupId, qId, null);
        final BasicDBObject query = new BasicDBObject(
                "pGroupId", normalizedCriteria.getpGroupId()).append(
                "pId", normalizedCriteria.getpId()).append(
                "qGroupId", normalizedCriteria.getqGroupId()).append(
                "qId", normalizedCriteria.getqId());

        writeMatches(collection, query, outputStream);
    }

    public void removeMatchesOutsideGroup(final MatchCollectionId collectionId,
                                          final String groupId)
            throws IllegalArgumentException, ObjectNotFoundException {

        validateRequiredParameter("collectionId", collectionId);
        validateRequiredParameter("groupId", groupId);

        final DBCollection collection = getExistingCollection(collectionId.getDbCollectionName());
        final BasicDBObject query = getOutsideGroupQuery(groupId);

        collection.remove(query);
    }

    public void saveMatches(final MatchCollectionId collectionId,
                            final List<CanvasMatches> matchesList)
            throws IllegalArgumentException {

        validateRequiredParameter("collectionId", collectionId);
        validateRequiredParameter("matchesList", matchesList);

        LOG.debug("saveMatches: entry, collectionId={}, matchesList.size()={}",
                  collectionId, matchesList.size());

        if (matchesList.size() > 0) {

            final DBCollection collection = matchDb.getCollection(collectionId.getDbCollectionName());

            ensureMatchIndexes(collection);

            final BulkWriteOperation bulk = collection.initializeUnorderedBulkOperation();

            DBObject matchesObject;
            for (final CanvasMatches canvasMatches : matchesList) {
                canvasMatches.normalize();
                matchesObject = (DBObject) JSON.parse(canvasMatches.toJson());
                bulk.insert(matchesObject);
            }

            final BulkWriteResult result = bulk.execute();

            if (LOG.isDebugEnabled()) {
                final String bulkResultMessage = getBulkResultMessage("matches", result, matchesList.size());
                LOG.debug("saveMatches: {} using {}.initializeUnorderedBulkOp()",
                          bulkResultMessage, collection.getFullName());
            }
        }
    }

    public void removeAllMatches(final MatchCollectionId collectionId)
            throws IllegalArgumentException, IOException, ObjectNotFoundException {

        LOG.debug("removeAllMatches: entry, collectionId={}", collectionId);

        validateRequiredParameter("collectionId", collectionId);

        final DBCollection collection = getExistingCollection(collectionId.getDbCollectionName());

        collection.drop();
    }

    private DBCollection getExistingCollection(final String collectionId)
            throws ObjectNotFoundException {
        if (! matchDb.collectionExists(collectionId)) {
            throw new ObjectNotFoundException("match collection '" + collectionId + "' does not exist");
        }
        return matchDb.getCollection(collectionId);
    }

    private void writeMatches(final DBCollection collection,
                              final BasicDBObject query,
                              final OutputStream outputStream)
            throws IOException {

        // exclude mongo id from results
        final BasicDBObject keys = new BasicDBObject("_id", 0);

        final ProcessTimer timer = new ProcessTimer();
        final byte[] openBracket = "[".getBytes();
        final byte[] commaWithNewline = ",\n".getBytes();
        final byte[] closeBracket = "]".getBytes();

        outputStream.write(openBracket);

        int count = 0;
        try (DBCursor cursor = collection.find(query, keys)) {

            DBObject document;
            while (cursor.hasNext()) {

                if (count > 0) {
                    outputStream.write(commaWithNewline);
                }

                document = cursor.next();
                outputStream.write(document.toString().getBytes());
                count++;

                if (timer.hasIntervalPassed()) {
                    LOG.debug("writeMatches: data written for {} matches", count);
                }
            }
        }

        outputStream.write(closeBracket);

        LOG.debug("writeMatches: wrote data for {} matches returned by {}.find({},{}), elapsedSeconds={}",
                  count, collection.getFullName(), query, keys, timer.getElapsedSeconds());
    }

    private BasicDBObject getOutsideGroupQuery(final String groupId) {
        final BasicDBList queryList = new BasicDBList();
        queryList.add(new BasicDBObject(
                "pGroupId", groupId).append(
                "qGroupId", new BasicDBObject(QueryOperators.NE, groupId)));
        queryList.add(new BasicDBObject(
                "qGroupId", groupId).append(
                "pGroupId", new BasicDBObject(QueryOperators.NE, groupId)));
        return new BasicDBObject(QueryOperators.OR, queryList);
    }

    private void validateRequiredParameter(final String context,
                                           final Object value)
            throws IllegalArgumentException {

        if (value == null) {
            throw new IllegalArgumentException(context + " value must be specified");
        }
    }

    private void ensureMatchIndexes(final DBCollection matchCollection) {
        LOG.debug("ensureMatchIndexes: entry, {}", matchCollection.getName());
        matchCollection.createIndex(new BasicDBObject(
                                            "pGroupId", 1).append(
                                            "qGroupId", 1).append(
                                            "pId", 1).append(
                                            "qId", 1),
                                    new BasicDBObject("unique", true).append("background", true));
        LOG.debug("ensureMatchIndexes: exit");
    }

    private String getBulkResultMessage(final String context,
                                        final BulkWriteResult result,
                                        final int objectCount) {

        final StringBuilder message = new StringBuilder(128);

        message.append("processed ").append(objectCount).append(" ").append(context);

        if (result.isAcknowledged()) {
            final int updates = result.getMatchedCount();
            final int inserts = objectCount - updates;
            message.append(" with ").append(inserts).append(" inserts and ");
            message.append(updates).append(" updates");
        } else {
            message.append(" (result NOT acknowledged)");
        }

        return message.toString();
    }

    private static final Logger LOG = LoggerFactory.getLogger(MatchDao.class);
}

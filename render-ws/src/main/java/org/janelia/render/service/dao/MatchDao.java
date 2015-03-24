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
import org.janelia.alignment.util.ProcessTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Data access object for Match database.
 *
 * @author Eric Trautman
 */
public class MatchDao {

    public static final String MATCH_DB_NAME = "match";

    private DB matchDb;

    public MatchDao(MongoClient client) {
        matchDb = client.getDB(MATCH_DB_NAME);
    }

    public void writeMatchesWithinLayer(String collectionName,
                                        double z,
                                        OutputStream outputStream)
            throws IllegalArgumentException, IOException {

        LOG.debug("writeMatchesWithinLayer: entry, collectionName={}, z={}",
                  collectionName, z);

        validateRequiredParameter("collectionName", collectionName);

        final DBCollection collection = matchDb.getCollection(collectionName);
        final BasicDBObject query = new BasicDBObject("pz", z).append("qz", z);

        writeMatches(collection, query, outputStream);
    }

    public void writeMatchesOutsideLayer(String collectionName,
                                         double z,
                                         OutputStream outputStream)
            throws IllegalArgumentException, IOException {

        LOG.debug("writeMatchesOutsideLayer: entry, collectionName={}, z={}",
                  collectionName, z);

        validateRequiredParameter("collectionName", collectionName);

        final DBCollection collection = matchDb.getCollection(collectionName);
        final BasicDBObject query = getOutsideLayerQuery(z);

        writeMatches(collection, query, outputStream);
    }

    public void removeMatchesOutsideLayer(String collectionName,
                                          double z)
            throws IllegalArgumentException {

        validateRequiredParameter("collectionName", collectionName);

        final DBCollection collection = matchDb.getCollection(collectionName);
        final BasicDBObject query = getOutsideLayerQuery(z);

        collection.remove(query);
    }

    public void saveMatches(String collectionName,
                            List<CanvasMatches> matchesList)
            throws IllegalArgumentException {

        validateRequiredParameter("collectionName", collectionName);
        validateRequiredParameter("matchesList", matchesList);

        LOG.debug("saveMatches: entry, collectionName={}, matchesList.size()={}",
                  collectionName, matchesList.size());

        if (matchesList.size() > 0) {

            final DBCollection collection = matchDb.getCollection(collectionName);

            ensureMatchIndexes(collection);

            final BulkWriteOperation bulk = collection.initializeUnorderedBulkOperation();

            DBObject matchesObject;
            for (CanvasMatches canvasMatches : matchesList) {
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

    private void writeMatches(DBCollection collection,
                              BasicDBObject query,
                              OutputStream outputStream)
            throws IllegalArgumentException, IOException {

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

    private BasicDBObject getOutsideLayerQuery(double z) {
        final BasicDBList queryList = new BasicDBList();
        queryList.add(new BasicDBObject("pz", z).append("qz", new BasicDBObject(QueryOperators.NE, z)));
        queryList.add(new BasicDBObject("qz", z).append("pz", new BasicDBObject(QueryOperators.NE, z)));
        return new BasicDBObject(QueryOperators.OR, queryList);
    }

    private void validateRequiredParameter(String context,
                                           Object value)
            throws IllegalArgumentException {

        if (value == null) {
            throw new IllegalArgumentException(context + " value must be specified");
        }
    }

    private void ensureMatchIndexes(DBCollection matchCollection) {
        LOG.debug("ensureMatchIndexes: entry, {}", matchCollection.getName());
        matchCollection.createIndex(new BasicDBObject("pz", 1).append("qz", 1).append("pId", 1).append("qId", 1),
                                    new BasicDBObject("unique", true).append("background", true));
        LOG.debug("ensureMatchIndexes: exit");
    }

    private String getBulkResultMessage(String context,
                                        BulkWriteResult result,
                                        int objectCount) {

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

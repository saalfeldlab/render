package org.janelia.render.service.dao;

import com.mongodb.MongoClient;
import com.mongodb.QueryOperators;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.InsertOneModel;
import com.mongodb.client.model.WriteModel;
import com.mongodb.client.result.DeleteResult;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
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

    private final MongoDatabase matchDatabase;

    public MatchDao(final MongoClient client) {
        matchDatabase = client.getDatabase(MATCH_DB_NAME);
    }

    public void writeMatchesWithinGroup(final MatchCollectionId collectionId,
                                        final String groupId,
                                        final OutputStream outputStream)
            throws IllegalArgumentException, IOException, ObjectNotFoundException {

        LOG.debug("writeMatchesWithinGroup: entry, collectionId={}, groupId={}",
                  collectionId, groupId);

        MongoUtil.validateRequiredParameter("collectionId", collectionId);
        MongoUtil.validateRequiredParameter("groupId", groupId);

        final MongoCollection<Document> collection = getExistingCollection(collectionId);
        final Document query = new Document("pGroupId", groupId).append("qGroupId", groupId);

        writeMatches(collection, query, outputStream);
    }

    public void writeMatchesOutsideGroup(final MatchCollectionId collectionId,
                                         final String groupId,
                                         final OutputStream outputStream)
            throws IllegalArgumentException, IOException, ObjectNotFoundException {

        LOG.debug("writeMatchesOutsideGroup: entry, collectionId={}, groupId={}",
                  collectionId, groupId);

        MongoUtil.validateRequiredParameter("collectionId", collectionId);
        MongoUtil.validateRequiredParameter("groupId", groupId);

        final MongoCollection<Document> collection = getExistingCollection(collectionId);
        final Document query = getOutsideGroupQuery(groupId);

        writeMatches(collection, query, outputStream);
    }

    public void writeMatchesBetweenGroups(final MatchCollectionId collectionId,
                                          final String pGroupId,
                                          final String qGroupId,
                                          final OutputStream outputStream)
            throws IllegalArgumentException, IOException, ObjectNotFoundException {

        LOG.debug("writeMatchesBetweenGroups: entry, collectionId={}, pGroupId={}, pGroupId={}",
                  collectionId, pGroupId, qGroupId);

        MongoUtil.validateRequiredParameter("collectionId", collectionId);
        MongoUtil.validateRequiredParameter("pGroupId", pGroupId);
        MongoUtil.validateRequiredParameter("qGroupId", qGroupId);

        final MongoCollection<Document> collection = getExistingCollection(collectionId);
        final String noTileId = "";
        final CanvasMatches normalizedCriteria = new CanvasMatches(pGroupId, noTileId, qGroupId, noTileId, null);
        final Document query = new Document(
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

        MongoUtil.validateRequiredParameter("collectionId", collectionId);
        MongoUtil.validateRequiredParameter("pGroupId", pGroupId);
        MongoUtil.validateRequiredParameter("pId", pId);
        MongoUtil.validateRequiredParameter("qGroupId", qGroupId);
        MongoUtil.validateRequiredParameter("qId", qId);

        final MongoCollection<Document> collection = getExistingCollection(collectionId);
        final CanvasMatches normalizedCriteria = new CanvasMatches(pGroupId, pId, qGroupId, qId, null);
        final Document query = new Document(
                "pGroupId", normalizedCriteria.getpGroupId()).append(
                "pId", normalizedCriteria.getpId()).append(
                "qGroupId", normalizedCriteria.getqGroupId()).append(
                "qId", normalizedCriteria.getqId());

        writeMatches(collection, query, outputStream);
    }

    public void removeMatchesOutsideGroup(final MatchCollectionId collectionId,
                                          final String groupId)
            throws IllegalArgumentException, ObjectNotFoundException {

        MongoUtil.validateRequiredParameter("collectionId", collectionId);
        MongoUtil.validateRequiredParameter("groupId", groupId);

        final MongoCollection<Document> collection = getExistingCollection(collectionId);
        final Document query = getOutsideGroupQuery(groupId);

        final DeleteResult result = collection.deleteMany(query);

        LOG.debug("removeMatchesOutsideGroup: removed {} matches using {}.delete({})",
                  result.getDeletedCount(), MongoUtil.fullName(collection), query.toJson());
    }

    public void saveMatches(final MatchCollectionId collectionId,
                            final List<CanvasMatches> matchesList)
            throws IllegalArgumentException {

        MongoUtil.validateRequiredParameter("collectionId", collectionId);
        MongoUtil.validateRequiredParameter("matchesList", matchesList);

        LOG.debug("saveMatches: entry, collectionId={}, matchesList.size()={}",
                  collectionId, matchesList.size());

        if (matchesList.size() > 0) {

            final MongoCollection<Document> collection = getExistingCollection(collectionId);

            ensureMatchIndexes(collection);

            final List<WriteModel<Document>> modelList = new ArrayList<>(matchesList.size());

            Document matchesObject;
            for (final CanvasMatches canvasMatches : matchesList) {
                canvasMatches.normalize();
                matchesObject = Document.parse(canvasMatches.toJson());
                modelList.add(new InsertOneModel<>(matchesObject));
            }

            final BulkWriteResult result = collection.bulkWrite(modelList, MongoUtil.UNORDERED_OPTION);

            if (LOG.isDebugEnabled()) {
                final String bulkResultMessage = MongoUtil.toMessage("matches", result, matchesList.size());
                LOG.debug("saveMatches: {} using {}.initializeUnorderedBulkOp()",
                          bulkResultMessage, MongoUtil.fullName(collection));
            }
        }
    }

    public void removeAllMatches(final MatchCollectionId collectionId)
            throws IllegalArgumentException, IOException, ObjectNotFoundException {

        LOG.debug("removeAllMatches: entry, collectionId={}", collectionId);

        MongoUtil.validateRequiredParameter("collectionId", collectionId);

        final MongoCollection<Document> collection = getExistingCollection(collectionId);

        collection.drop();
    }

    private MongoCollection<Document> getExistingCollection(final MatchCollectionId collectionId) {
        return MongoUtil.getExistingCollection(matchDatabase, collectionId.getDbCollectionName());
    }

    private void writeMatches(final MongoCollection<Document> collection,
                              final Document query,
                              final OutputStream outputStream)
            throws IOException {

        // exclude mongo id from results
        final Document keys = new Document("_id", 0);

        final ProcessTimer timer = new ProcessTimer();
        final byte[] openBracket = "[".getBytes();
        final byte[] commaWithNewline = ",\n".getBytes();
        final byte[] closeBracket = "]".getBytes();

        outputStream.write(openBracket);

        int count = 0;
        try (MongoCursor<Document> cursor = collection.find(query).projection(keys).iterator()) {

            Document document;
            while (cursor.hasNext()) {

                if (count > 0) {
                    outputStream.write(commaWithNewline);
                }

                document = cursor.next();
                outputStream.write(document.toJson().getBytes());
                count++;

                if (timer.hasIntervalPassed()) {
                    LOG.debug("writeMatches: data written for {} matches", count);
                }
            }
        }

        outputStream.write(closeBracket);

        LOG.debug("writeMatches: wrote data for {} matches returned by {}.find({},{}), elapsedSeconds={}",
                  count, MongoUtil.fullName(collection), query.toJson(), keys.toJson(), timer.getElapsedSeconds());
    }

    private Document getOutsideGroupQuery(final String groupId) {
        final List<Document> queryList = new ArrayList<>();
        queryList.add(new Document("pGroupId", groupId).append(
                "qGroupId", new Document(QueryOperators.NE, groupId)));
        queryList.add(new Document("qGroupId", groupId).append(
                "pGroupId", new Document(QueryOperators.NE, groupId)));
        return new Document(QueryOperators.OR, queryList);
    }

    private void ensureMatchIndexes(final MongoCollection<Document> collection) {
        MongoUtil.createIndex(collection,
                              new Document("pGroupId", 1).append(
                                      "qGroupId", 1).append(
                                      "pId", 1).append(
                                      "qId", 1),
                              new IndexOptions().unique(true).background(true));
    }

    private static final Logger LOG = LoggerFactory.getLogger(MatchDao.class);
}

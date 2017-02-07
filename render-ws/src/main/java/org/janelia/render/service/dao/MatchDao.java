package org.janelia.render.service.dao;

import com.mongodb.MongoClient;
import com.mongodb.QueryOperators;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.WriteModel;
import com.mongodb.client.result.DeleteResult;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.bson.Document;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.match.MatchCollectionMetaData;
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

    /**
     * @return list of match collection metadata.
     */
    public List<MatchCollectionMetaData> getMatchCollectionMetaData()
            throws IllegalArgumentException {

        final List<MatchCollectionMetaData> list = new ArrayList<>();
        for (final String collectionName : matchDatabase.listCollectionNames()) {
            if (! collectionName.startsWith("system.")) {
                list.add(
                        new MatchCollectionMetaData(
                                MatchCollectionId.fromDbCollectionName(collectionName),
                                matchDatabase.getCollection(collectionName).count()));
            }
        }

        return list;
    }

    /**
     * @return list of distinct pGroupIds in the specified collection.
     */
    public List<String> getDistinctPGroupIds(final MatchCollectionId collectionId)
            throws IllegalArgumentException {
        return getDistinctIdsForField(collectionId, "pGroupId");
    }

    /**
     * @return list of distinct qGroupIds in the specified collection.
     */
    public List<String> getDistinctQGroupIds(final MatchCollectionId collectionId)
            throws IllegalArgumentException {
        return getDistinctIdsForField(collectionId, "qGroupId");
    }

    /**
     * @return list of distinct p and q groupIds in the specified collection.
     */
    public List<String> getDistinctGroupIds(final MatchCollectionId collectionId)
            throws IllegalArgumentException {
        final Set<String> groupIds = new TreeSet<>();
        groupIds.addAll(getDistinctPGroupIds(collectionId));
        groupIds.addAll(getDistinctQGroupIds(collectionId));
        return new ArrayList<>(groupIds);
    }

    public void writeMatchesWithPGroup(final MatchCollectionId collectionId,
                                       final List<MatchCollectionId> mergeCollectionIdList,
                                       final String pGroupId,
                                       final OutputStream outputStream)
            throws IllegalArgumentException, IOException, ObjectNotFoundException {

        LOG.debug("writeMatchesWithPGroup: entry, collectionId={}, mergeCollectionIdList={}, pGroupId={}",
                  collectionId, mergeCollectionIdList, pGroupId);

        final List<MongoCollection<Document>> collectionList = getDistinctCollectionList(collectionId,
                                                                                         mergeCollectionIdList);
        MongoUtil.validateRequiredParameter("pGroupId", pGroupId);

        final Document query = new Document("pGroupId", pGroupId);

        writeMatches(collectionList, query, outputStream);
    }

    public void writeMatchesWithinGroup(final MatchCollectionId collectionId,
                                        final List<MatchCollectionId> mergeCollectionIdList,
                                        final String groupId,
                                        final OutputStream outputStream)
            throws IllegalArgumentException, IOException, ObjectNotFoundException {

        LOG.debug("writeMatchesWithinGroup: entry, collectionId={}, mergeCollectionIdList={}, groupId={}",
                  collectionId, mergeCollectionIdList, groupId);

        final List<MongoCollection<Document>> collectionList = getDistinctCollectionList(collectionId,
                                                                                         mergeCollectionIdList);
        MongoUtil.validateRequiredParameter("groupId", groupId);

        final Document query = new Document("pGroupId", groupId).append("qGroupId", groupId);

        writeMatches(collectionList, query, outputStream);
    }

    public void writeMatchesOutsideGroup(final MatchCollectionId collectionId,
                                         final List<MatchCollectionId> mergeCollectionIdList,
                                         final String groupId,
                                         final OutputStream outputStream)
            throws IllegalArgumentException, IOException, ObjectNotFoundException {

        LOG.debug("writeMatchesOutsideGroup: entry, collectionId={}, mergeCollectionIdList={}, groupId={}",
                  collectionId, mergeCollectionIdList, groupId);

        final List<MongoCollection<Document>> collectionList = getDistinctCollectionList(collectionId,
                                                                                         mergeCollectionIdList);
        MongoUtil.validateRequiredParameter("groupId", groupId);

        final Document query = getOutsideGroupQuery(groupId);

        writeMatches(collectionList, query, outputStream);
    }

    public void writeMatchesBetweenGroups(final MatchCollectionId collectionId,
                                          final List<MatchCollectionId> mergeCollectionIdList,
                                          final String pGroupId,
                                          final String qGroupId,
                                          final OutputStream outputStream)
            throws IllegalArgumentException, IOException, ObjectNotFoundException {

        LOG.debug("writeMatchesBetweenGroups: entry, collectionId={}, mergeCollectionIdList={}, pGroupId={}, pGroupId={}",
                  collectionId, mergeCollectionIdList, pGroupId, qGroupId);

        final List<MongoCollection<Document>> collectionList = getDistinctCollectionList(collectionId,
                                                                                         mergeCollectionIdList);
        MongoUtil.validateRequiredParameter("pGroupId", pGroupId);
        MongoUtil.validateRequiredParameter("qGroupId", qGroupId);

        final String noTileId = "";
        final CanvasMatches normalizedCriteria = new CanvasMatches(pGroupId, noTileId, qGroupId, noTileId, null);
        final Document query = new Document(
                "pGroupId", normalizedCriteria.getpGroupId()).append(
                "qGroupId", normalizedCriteria.getqGroupId());

        writeMatches(collectionList, query, outputStream);
    }

    public void writeMatchesBetweenObjects(final MatchCollectionId collectionId,
                                           final List<MatchCollectionId> mergeCollectionIdList,
                                           final String pGroupId,
                                           final String pId,
                                           final String qGroupId,
                                           final String qId,
                                           final OutputStream outputStream)
            throws IllegalArgumentException, IOException, ObjectNotFoundException {

        LOG.debug("writeMatchesBetweenObjects: entry, collectionId={}, mergeCollectionIdList={}, pGroupId={}, pId={}, qGroupId={}, qId={}",
                  collectionId, mergeCollectionIdList, pGroupId, pId, qGroupId, qId);

        final List<MongoCollection<Document>> collectionList = getDistinctCollectionList(collectionId,
                                                                                         mergeCollectionIdList);
        MongoUtil.validateRequiredParameter("pGroupId", pGroupId);
        MongoUtil.validateRequiredParameter("pId", pId);
        MongoUtil.validateRequiredParameter("qGroupId", qGroupId);
        MongoUtil.validateRequiredParameter("qId", qId);

        final CanvasMatches normalizedCriteria = new CanvasMatches(pGroupId, pId, qGroupId, qId, null);
        final Document query = new Document(
                "pGroupId", normalizedCriteria.getpGroupId()).append(
                "pId", normalizedCriteria.getpId()).append(
                "qGroupId", normalizedCriteria.getqGroupId()).append(
                "qId", normalizedCriteria.getqId());

        writeMatches(collectionList, query, outputStream);
    }
    public void writeMatchesInvolvingObject(final MatchCollectionId collectionId,
								           final List<MatchCollectionId> mergeCollectionIdList,
								           final String groupId,
								           final String id,
								           final OutputStream outputStream)
			throws IllegalArgumentException, IOException, ObjectNotFoundException {
			
			LOG.debug("writeMatchesInvolvingObject: entry, collectionId={}, mergeCollectionIdList={}, groupId={}, id={}",
			collectionId, mergeCollectionIdList, groupId, id);
			
			final List<MongoCollection<Document>> collectionList = getDistinctCollectionList(collectionId,
			                                                          mergeCollectionIdList);
			MongoUtil.validateRequiredParameter("groupId", groupId);
			MongoUtil.validateRequiredParameter("id", id);
					
			final Document query = getInvolvingObjectQuery(groupId,id);	
			
			writeMatches(collectionList, query, outputStream);
	}
    public void removeMatchesBetweenTiles(final MatchCollectionId collectionId,
                                          final String pGroupId,
                                          final String pId,
                                          final String qGroupId,
                                          final String qId)
            throws IllegalArgumentException, ObjectNotFoundException {

        LOG.debug("removeMatchesBetweenTiles: entry, collectionId={}, pGroupId={}, pId={}, qGroupId={}, qId={}",
                  collectionId, pGroupId, pId, qGroupId, qId);

        final MongoCollection<Document> collection = getExistingCollection(collectionId);

        MongoUtil.validateRequiredParameter("pGroupId", pGroupId);
        MongoUtil.validateRequiredParameter("pId", pId);
        MongoUtil.validateRequiredParameter("qGroupId", qGroupId);
        MongoUtil.validateRequiredParameter("qId", qId);

        final CanvasMatches normalizedCriteria = new CanvasMatches(pGroupId, pId, qGroupId, qId, null);
        final Document query = new Document(
                "pGroupId", normalizedCriteria.getpGroupId()).append(
                "pId", normalizedCriteria.getpId()).append(
                "qGroupId", normalizedCriteria.getqGroupId()).append(
                "qId", normalizedCriteria.getqId());

        final DeleteResult result = collection.deleteMany(query);

        LOG.debug("removeMatchesBetweenTiles: removed {} matches using {}.delete({})",
                  result.getDeletedCount(), MongoUtil.fullName(collection), query.toJson());
    }

    public void removeMatchesBetweenGroups(final MatchCollectionId collectionId,
                                           final String pGroupId,
                                           final String qGroupId)
            throws IllegalArgumentException, ObjectNotFoundException {

        LOG.debug("removeMatchesBetweenGroups: entry, collectionId={}, pGroupId={}, qGroupId={}",
                  collectionId, pGroupId,  qGroupId);

        final MongoCollection<Document> collection = getExistingCollection(collectionId);

        MongoUtil.validateRequiredParameter("pGroupId", pGroupId);
        MongoUtil.validateRequiredParameter("qGroupId", qGroupId);

        final String noTileId = "";
        final CanvasMatches normalizedCriteria = new CanvasMatches(pGroupId, noTileId, qGroupId, noTileId, null);
        final Document query = new Document(
                "pGroupId", normalizedCriteria.getpGroupId()).append(
                "qGroupId", normalizedCriteria.getqGroupId());

        final DeleteResult result = collection.deleteMany(query);

        LOG.debug("removeMatchesBetweenGroups: removed {} matches using {}.delete({})",
                  result.getDeletedCount(), MongoUtil.fullName(collection), query.toJson());
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

            final MongoCollection<Document> collection =
                    matchDatabase.getCollection(collectionId.getDbCollectionName());

            ensureMatchIndexes(collection);

            final List<WriteModel<Document>> modelList = new ArrayList<>(matchesList.size());

            final UpdateOptions upsertOption = new UpdateOptions().upsert(true);
            Document filter;
            Document matchesObject;
            for (final CanvasMatches canvasMatches : matchesList) {
                canvasMatches.normalize();
                filter = new Document(
                        "pGroupId", canvasMatches.getpGroupId()).append(
                        "pId", canvasMatches.getpId()).append(
                        "qGroupId", canvasMatches.getqGroupId()).append(
                        "qId", canvasMatches.getqId());
                matchesObject = Document.parse(canvasMatches.toJson());
                modelList.add(new ReplaceOneModel<>(filter, matchesObject, upsertOption));
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

    private List<MongoCollection<Document>> getDistinctCollectionList(final MatchCollectionId collectionId,
                                                                      final List<MatchCollectionId> mergeCollectionIdList) {

        MongoUtil.validateRequiredParameter("collectionId", collectionId);

        final Set<MatchCollectionId> collectionIdSet = new HashSet<>();
        final List<MongoCollection<Document>> collectionList = new ArrayList<>();

        collectionIdSet.add(collectionId);
        collectionList.add(getExistingCollection(collectionId));

        if ((mergeCollectionIdList != null) && (mergeCollectionIdList.size() > 0)) {
            for (final MatchCollectionId mergeCollectionId : mergeCollectionIdList) {
                if (collectionIdSet.add(mergeCollectionId)) {
                    collectionList.add(getExistingCollection(mergeCollectionId));
                } else {
                    LOG.warn("filtered duplicate collection id {}", mergeCollectionId);
                }
            }
        }

        return collectionList;
    }

    private List<String> getDistinctIdsForField(final MatchCollectionId collectionId,
                                                final String fieldName) {

        MongoUtil.validateRequiredParameter("collectionId", collectionId);

        final List<String> distinctIds = new ArrayList<>(8096);

        final MongoCollection<Document> collection = getExistingCollection(collectionId);

        try (MongoCursor<String> cursor = collection.distinct(fieldName, String.class).iterator()) {
            while (cursor.hasNext()) {
                distinctIds.add(cursor.next());
            }
        }

        return distinctIds;
    }

    private void writeMatches(final List<MongoCollection<Document>> collectionList,
                              final Document query,
                              final OutputStream outputStream)
            throws IOException {

        if (collectionList.size() > 1) {

            writeMergedMatches(collectionList, query, outputStream);

        } else {

            final MongoCollection<Document> collection = collectionList.get(0);

            final ProcessTimer timer = new ProcessTimer();

            outputStream.write(OPEN_BRACKET);

            int count = 0;
            try (MongoCursor<Document> cursor = collection.find(query).projection(EXCLUDE_MONGO_ID_KEY).sort(MATCH_ORDER_BY).iterator()) {

                Document document;
                while (cursor.hasNext()) {

                    if (count > 0) {
                        outputStream.write(COMMA_WITH_NEW_LINE);
                    }

                    document = cursor.next();
                    outputStream.write(document.toJson().getBytes());
                    count++;

                    if (timer.hasIntervalPassed()) {
                        LOG.debug("writeMatches: data written for {} matches", count);
                    }
                }
            }

            outputStream.write(CLOSE_BRACKET);

            if (LOG.isDebugEnabled()) {
                LOG.debug("writeMatches: wrote data for {} matches returned by {}.find({},{}), elapsedSeconds={}",
                          count, MongoUtil.fullName(collection), query.toJson(), EXCLUDE_MONGO_ID_KEY_JSON, timer.getElapsedSeconds());
            }
        }
    }

    private void writeMergedMatches(final List<MongoCollection<Document>> collectionList,
                                    final Document query,
                                    final OutputStream outputStream)
            throws IOException {

        // exclude mongo id from results
        final ProcessTimer timer = new ProcessTimer();

        outputStream.write(OPEN_BRACKET);

        int count = 0;

        final int numberOfCollections = collectionList.size();
        final List<MongoCursor<Document>> cursorList = new ArrayList<>(numberOfCollections);
        final List<CanvasMatches> matchesList = new ArrayList<>(numberOfCollections);

        try {

            int numberOfCompletedCursors = 0;
            MongoCollection<Document> collection;
            for (int i = 0; i < numberOfCollections; i++) {
                collection = collectionList.get(i);
                cursorList.add(collection.find(query).projection(EXCLUDE_MONGO_ID_KEY).sort(MATCH_ORDER_BY).iterator());
                matchesList.add(null);
                numberOfCompletedCursors += updateMatches(cursorList, matchesList, i);
            }

            if (numberOfCompletedCursors > 0) {
                removeCompletedCursors(cursorList, matchesList);
            }

            CanvasMatches mergedMatches;
            while (matchesList.size() > 0) {
                if (count > 0) {
                    outputStream.write(COMMA_WITH_NEW_LINE);
                }

                mergedMatches = getNextMergedMatches(cursorList, matchesList);

                outputStream.write(mergedMatches.toJson().getBytes());
                count++;

                if (timer.hasIntervalPassed()) {
                    LOG.debug("writeMergedMatches: data written for {} matches", count);
                }
            }

        } finally {

            for (final MongoCursor<Document> cursor : cursorList) {
                if (cursor != null) {
                    try {
                        cursor.close();
                    } catch (final Throwable t) {
                        LOG.error("failed to close cursor, ignoring exception", t);
                    }
                }
            }

        }

        outputStream.write(CLOSE_BRACKET);

        if (LOG.isDebugEnabled()) {
            final StringBuilder collectionNames = new StringBuilder(512);
            for (int i = 0; i < collectionList.size(); i++) {
                if (i > 0) {
                    collectionNames.append('|');
                }
                collectionNames.append(MongoUtil.fullName(collectionList.get(i)));
            }
            LOG.debug("writeMergedMatches: wrote data for {} matches returned by {}.find({},{}).sort({}), elapsedSeconds={}",
                      count, collectionNames, query.toJson(), EXCLUDE_MONGO_ID_KEY_JSON, MATCH_ORDER_BY_JSON, timer.getElapsedSeconds());
        }
    }

    private CanvasMatches getNextMergedMatches(final List<MongoCursor<Document>> cursorList,
                                               final List<CanvasMatches> matchesList) {

        int numberOfCompletedCursors = 0;
        int nextMatchesIndex = 0;
        CanvasMatches nextMatches = matchesList.get(nextMatchesIndex);
        CanvasMatches matches;
        int comparisonResult;
        for (int i = 1; i < matchesList.size(); i++) {
            matches = matchesList.get(i);
            comparisonResult = matches.compareTo(nextMatches);
            if (comparisonResult == 0) {
                nextMatches.append(matches.getMatches());
                numberOfCompletedCursors += updateMatches(cursorList, matchesList, i);
            } else if (comparisonResult < 0) {
                nextMatchesIndex = i;
                nextMatches = matches;
            }
        }

        numberOfCompletedCursors += updateMatches(cursorList, matchesList, nextMatchesIndex);

        if (numberOfCompletedCursors > 0) {
            removeCompletedCursors(cursorList, matchesList);
        }

        return nextMatches;
    }

    private void removeCompletedCursors(final List<MongoCursor<Document>> cursorList,
                                        final List<CanvasMatches> matchesList) {
        MongoCursor<Document> cursor;
        for (int i = matchesList.size() - 1; i >=0; i--) {
            if (matchesList.get(i) == null) {
                matchesList.remove(i);
                cursor = cursorList.remove(i);
                cursor.close();
            }
        }
    }

    private int updateMatches(final List<MongoCursor<Document>> cursorList,
                              final List<CanvasMatches> matchesList,
                              final int index) {
        CanvasMatches canvasMatches = null;
        final MongoCursor<Document> cursor = cursorList.get(index);
        if (cursor.hasNext()) {
            canvasMatches = CanvasMatches.fromJson(cursor.next().toJson());
        }
        matchesList.set(index, canvasMatches);
        return (canvasMatches == null ? 1 : 0);
    }

    private Document getOutsideGroupQuery(final String groupId) {
        final List<Document> queryList = new ArrayList<>();
        queryList.add(new Document("pGroupId", groupId).append(
                "qGroupId", new Document(QueryOperators.NE, groupId)));
        queryList.add(new Document("qGroupId", groupId).append(
                "pGroupId", new Document(QueryOperators.NE, groupId)));
        return new Document(QueryOperators.OR, queryList);
    }
    
    private Document getInvolvingObjectQuery(final String groupId,final String Id){
    	 final List<Document> queryList = new ArrayList<>();
         queryList.add(new Document("pGroupId", groupId).append(
                 "pId", Id));
         queryList.add(new Document("qGroupId", groupId).append(
                 "qId", Id));
         return new Document(QueryOperators.OR, queryList);
    }

    private void ensureMatchIndexes(final MongoCollection<Document> collection) {
        MongoUtil.createIndex(collection,
                              new Document("pGroupId", 1).append(
                                      "qGroupId", 1).append(
                                      "pId", 1).append(
                                      "qId", 1),
                              MATCH_A_OPTIONS);
        MongoUtil.createIndex(collection,
                              new Document("qGroupId", 1),
                              MATCH_B_OPTIONS);
    }

    private static final Logger LOG = LoggerFactory.getLogger(MatchDao.class);

    private static final Document MATCH_ORDER_BY =
            new Document("pGroupId", 1).append("qGroupId", 1).append("pId", 1).append("qId", 1);
    private static final String MATCH_ORDER_BY_JSON = MATCH_ORDER_BY.toJson();
    private static final Document EXCLUDE_MONGO_ID_KEY = new Document("_id", 0);
    private static final String EXCLUDE_MONGO_ID_KEY_JSON = EXCLUDE_MONGO_ID_KEY.toJson();
    private static final byte[] OPEN_BRACKET = "[".getBytes();
    private static final byte[] COMMA_WITH_NEW_LINE = ",\n".getBytes();
    private static final byte[] CLOSE_BRACKET = "]".getBytes();

    private static final IndexOptions MATCH_A_OPTIONS = new IndexOptions().unique(true).background(true).name("A");
    private static final IndexOptions MATCH_B_OPTIONS = new IndexOptions().background(true).name("B");

}

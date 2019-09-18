package org.janelia.render.service.dao;

import com.mongodb.MongoClient;
import com.mongodb.QueryOperators;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.WriteModel;
import com.mongodb.client.result.DeleteResult;

import java.io.IOException;
import java.io.OutputStream;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.match.MatchCollectionMetaData;
import org.janelia.alignment.match.MatchTrial;
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

    static final String MATCH_DB_NAME = "match";
    static final String MATCH_TRIAL_COLLECTION_NAME = "aaa_match_trial";

    public static MatchDao build()
            throws UnknownHostException {
        final MongoClient mongoClient = SharedMongoClient.getInstance();
        return new MatchDao(mongoClient);
    }

    private final MongoDatabase matchDatabase;

    MatchDao(final MongoClient client) {
        matchDatabase = client.getDatabase(MATCH_DB_NAME);
    }

    /**
     * @return list of match collection metadata.
     */
    public List<MatchCollectionMetaData> getMatchCollectionMetaData()
            throws IllegalArgumentException {

        final List<MatchCollectionMetaData> list = new ArrayList<>();
        for (final String collectionName : matchDatabase.listCollectionNames()) {
            if (! collectionName.startsWith("system.") && (! MATCH_TRIAL_COLLECTION_NAME.equals(collectionName))) {
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

    /**
     * Finds p sections that have multiple (split) cross layer consensus set match pairs.
     *
     * @return list of pGroupIds for pGroupId/qGroupId combinations that have more than one match pair
     *         in the specified collection.
     */
    public List<String> getMultiConsensusPGroupIds(final MatchCollectionId collectionId)
            throws IllegalArgumentException {
        return new ArrayList<>(getMultiConsensusGroupIds(collectionId, false));
    }

    /**
     * Finds all sections that have multiple (split) cross layer consensus set match pairs.
     *
     * @return distinct set of p and q group ids for pGroupId/qGroupId combinations that have more than one match pair
     *         in the specified collection.
     */
    public Set<String> getMultiConsensusGroupIds(final MatchCollectionId collectionId)
            throws IllegalArgumentException {

        return getMultiConsensusGroupIds(collectionId, true);
    }

    public void writeMatchesWithPGroup(final MatchCollectionId collectionId,
                                       final List<MatchCollectionId> mergeCollectionIdList,
                                       final String pGroupId,
                                       final boolean excludeMatchDetails,
                                       final OutputStream outputStream)
            throws IllegalArgumentException, IOException, ObjectNotFoundException {

        LOG.debug("writeMatchesWithPGroup: entry, collectionId={}, mergeCollectionIdList={}, pGroupId={}",
                  collectionId, mergeCollectionIdList, pGroupId);

        final List<MongoCollection<Document>> collectionList = getDistinctCollectionList(collectionId,
                                                                                         mergeCollectionIdList);
        MongoUtil.validateRequiredParameter("pGroupId", pGroupId);

        final Document query = new Document("pGroupId", pGroupId);

        writeMatches(collectionList, query, excludeMatchDetails, outputStream);
    }

    public void writeMatchesWithinGroup(final MatchCollectionId collectionId,
                                        final List<MatchCollectionId> mergeCollectionIdList,
                                        final String groupId,
                                        final boolean excludeMatchDetails,
                                        final OutputStream outputStream)
            throws IllegalArgumentException, IOException, ObjectNotFoundException {

        LOG.debug("writeMatchesWithinGroup: entry, collectionId={}, mergeCollectionIdList={}, groupId={}",
                  collectionId, mergeCollectionIdList, groupId);

        final List<MongoCollection<Document>> collectionList = getDistinctCollectionList(collectionId,
                                                                                         mergeCollectionIdList);
        MongoUtil.validateRequiredParameter("groupId", groupId);

        final Document query = new Document("pGroupId", groupId).append("qGroupId", groupId);

        writeMatches(collectionList, query, excludeMatchDetails, outputStream);
    }

    public void writeMatchesOutsideGroup(final MatchCollectionId collectionId,
                                         final List<MatchCollectionId> mergeCollectionIdList,
                                         final String groupId,
                                         final boolean excludeMatchDetails,
                                         final OutputStream outputStream)
            throws IllegalArgumentException, IOException, ObjectNotFoundException {

        LOG.debug("writeMatchesOutsideGroup: entry, collectionId={}, mergeCollectionIdList={}, groupId={}",
                  collectionId, mergeCollectionIdList, groupId);

        final List<MongoCollection<Document>> collectionList = getDistinctCollectionList(collectionId,
                                                                                         mergeCollectionIdList);
        MongoUtil.validateRequiredParameter("groupId", groupId);

        final Document query = getOutsideGroupQuery(groupId);

        writeMatches(collectionList, query, excludeMatchDetails, outputStream);
    }

    public List<CanvasMatches> getMatchesWithinGroup(final MatchCollectionId collectionId,
                                                     final String groupId,
                                                     final boolean excludeMatchDetails)
            throws IllegalArgumentException, ObjectNotFoundException {

        LOG.debug("getMatchesWithinGroup: entry, collectionId={}, groupId={}",
                  collectionId, groupId);

        final MongoCollection<Document> collection = getExistingCollection(collectionId);

        MongoUtil.validateRequiredParameter("groupId", groupId);

        final Document query = new Document("pGroupId", groupId).append("qGroupId", groupId);

        return getMatches(collection, query, excludeMatchDetails);
    }

    public List<CanvasMatches> getMatchesOutsideGroup(final MatchCollectionId collectionId,
                                                      final String groupId,
                                                      final boolean excludeMatchDetails)
            throws IllegalArgumentException, ObjectNotFoundException {

        LOG.debug("getMatchesOutsideGroup: entry, collectionId={}, groupId={}",
                  collectionId, groupId);

        final MongoCollection<Document> collection = getExistingCollection(collectionId);

        MongoUtil.validateRequiredParameter("groupId", groupId);

        final Document query = getOutsideGroupQuery(groupId);

        return getMatches(collection, query, excludeMatchDetails);
    }

    public List<CanvasMatches> getMatchesBetweenGroups(final MatchCollectionId collectionId,
                                                       final String pGroupId,
                                                       final String qGroupId,
                                                       final boolean excludeMatchDetails)
            throws IllegalArgumentException, ObjectNotFoundException {

        LOG.debug("getMatchesBetweenGroups: entry, collectionId={}, pGroupId={}, qGroupId={}",
                  collectionId, pGroupId, qGroupId);

        final MongoCollection<Document> collection = getExistingCollection(collectionId);

        validateRequiredGroupIds(pGroupId, qGroupId);

        final Document query = getNormalizedGroupIdQuery(pGroupId, qGroupId);

        return getMatches(collection, query, excludeMatchDetails);
    }

    public void writeMatchesBetweenGroups(final MatchCollectionId collectionId,
                                          final List<MatchCollectionId> mergeCollectionIdList,
                                          final String pGroupId,
                                          final String qGroupId,
                                          final boolean excludeMatchDetails,
                                          final OutputStream outputStream)
            throws IllegalArgumentException, IOException, ObjectNotFoundException {

        LOG.debug("writeMatchesBetweenGroups: entry, collectionId={}, mergeCollectionIdList={}, pGroupId={}, qGroupId={}",
                  collectionId, mergeCollectionIdList, pGroupId, qGroupId);

        validateRequiredGroupIds(pGroupId, qGroupId);

        final List<MongoCollection<Document>> collectionList = getDistinctCollectionList(collectionId,
                                                                                         mergeCollectionIdList);
        final Document query = getNormalizedGroupIdQuery(pGroupId, qGroupId);

        writeMatches(collectionList, query, excludeMatchDetails, outputStream);
    }

    public void writeMatchesBetweenObjectAndGroup(final MatchCollectionId collectionId,
                                                  final List<MatchCollectionId> mergeCollectionIdList,
                                                  final String pGroupId,
                                                  final String pId,
                                                  final String qGroupId,
                                                  final boolean excludeMatchDetails,
                                                  final OutputStream outputStream)
            throws IllegalArgumentException, IOException, ObjectNotFoundException {

        LOG.debug("writeMatchesBetweenObjectandGroup: entry, collectionId={}, mergeCollectionIdList={}, pGroupId={}, pId={}, qGroupId={}",
                  collectionId, mergeCollectionIdList, pGroupId, pId, qGroupId);

        final List<MongoCollection<Document>> collectionList = getDistinctCollectionList(collectionId,
                                                                                         mergeCollectionIdList);
        MongoUtil.validateRequiredParameter("pGroupId", pGroupId);
        MongoUtil.validateRequiredParameter("pId", pId);
        MongoUtil.validateRequiredParameter("qGroupId", qGroupId);

        final Document query = getInvolvingObjectAndGroupQuery(pGroupId, pId, qGroupId);

        writeMatches(collectionList, query, excludeMatchDetails, outputStream);
    }

    public CanvasMatches getMatchesBetweenObjects(final MatchCollectionId collectionId,
                                                  final String pGroupId,
                                                  final String pId,
                                                  final String qGroupId,
                                                  final String qId)
            throws IllegalArgumentException, ObjectNotFoundException {

        validateRequiredPairIds(pGroupId, pId, qGroupId, qId);

        final Document query = getNormalizedIdQuery(pGroupId, pId, qGroupId, qId);

        final MongoCollection<Document> collection = getExistingCollection(collectionId);

        int matchCount = 0;
        CanvasMatches canvasMatches = null;
        try (final MongoCursor<Document> cursor = collection.find(query).iterator()) {
            if (cursor.hasNext()) {
                canvasMatches = CanvasMatches.fromJson(cursor.next().toJson());
                matchCount = canvasMatches.size();
            }
        }

        if (matchCount == 0) {
            throw new ObjectNotFoundException(collectionId + " does not contain matches between " +
                                              pId + " and " + qId);
        }

        LOG.debug("getMatchesBetweenObjects: returning {} matches for {}.find({})",
                  matchCount, collection.getNamespace().getFullName(), query.toJson());

        return canvasMatches;
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

        validateRequiredPairIds(pGroupId, pId, qGroupId, qId);

        final List<MongoCollection<Document>> collectionList = getDistinctCollectionList(collectionId,
                                                                                         mergeCollectionIdList);

        final Document query = getNormalizedIdQuery(pGroupId, pId, qGroupId, qId);

        writeMatches(collectionList, query, false, outputStream);
    }

    public void writeMatchesInvolvingObject(final MatchCollectionId collectionId,
                                            final List<MatchCollectionId> mergeCollectionIdList,
                                            final String groupId,
                                            final String id,
                                            final OutputStream outputStream)
            throws IllegalArgumentException, IOException, ObjectNotFoundException {

        LOG.debug("writeMatchesInvolvingObject: entry, collectionId={}, mergeCollectionIdList={}, groupId={}, id={}",
                  collectionId, mergeCollectionIdList, groupId, id);

        validateRequiredCanvasIds(groupId, id);
        final List<MongoCollection<Document>> collectionList = getDistinctCollectionList(collectionId,
                                                                                         mergeCollectionIdList);
        final Document query = getInvolvingObjectQuery(groupId, id);

        writeMatches(collectionList, query, false, outputStream);
    }

    public void removeMatchesInvolvingObject(final MatchCollectionId collectionId,
                                             final String groupId,
                                             final String id)
            throws IllegalArgumentException, ObjectNotFoundException {

        LOG.debug("removeMatchesInvolvingObject: entry, collectionId={}, groupId={}, id={}",
                  collectionId, groupId, id);

        validateRequiredCanvasIds(groupId, id);
        final MongoCollection<Document> collection = getExistingCollection(collectionId);
        final Document query = getInvolvingObjectQuery(groupId, id);

        final DeleteResult result = collection.deleteMany(query);

        LOG.debug("removeMatchesInvolvingObject: removed {} matches using {}.delete({})",
                  result.getDeletedCount(), MongoUtil.fullName(collection), query.toJson());
    }

    public void removeMatchesBetweenTiles(final MatchCollectionId collectionId,
                                          final String pGroupId,
                                          final String pId,
                                          final String qGroupId,
                                          final String qId)
            throws IllegalArgumentException, ObjectNotFoundException {

        LOG.debug("removeMatchesBetweenTiles: entry, collectionId={}, pGroupId={}, pId={}, qGroupId={}, qId={}",
                  collectionId, pGroupId, pId, qGroupId, qId);

        validateRequiredPairIds(pGroupId, pId, qGroupId, qId);
        final MongoCollection<Document> collection = getExistingCollection(collectionId);
        final Document query = getNormalizedIdQuery(pGroupId, pId, qGroupId, qId);

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

        validateRequiredGroupIds(pGroupId, qGroupId);
        final MongoCollection<Document> collection = getExistingCollection(collectionId);
        final Document query = getNormalizedGroupIdQuery(pGroupId, qGroupId);

        final DeleteResult result = collection.deleteMany(query);

        LOG.debug("removeMatchesBetweenGroups: removed {} matches using {}.delete({})",
                  result.getDeletedCount(), MongoUtil.fullName(collection), query.toJson());
    }

    public void removeMatchesWithPGroup(final MatchCollectionId collectionId,
                                        final String pGroupId)
            throws IllegalArgumentException, ObjectNotFoundException {

        LOG.debug("removeMatchesWithPGroup: entry, collectionId={}, pGroupId={}",
                  collectionId, pGroupId);

        MongoUtil.validateRequiredParameter("pGroupId", pGroupId);

        final MongoCollection<Document> collection = getExistingCollection(collectionId);

        final Document query = new Document("pGroupId", pGroupId);

        final DeleteResult result = collection.deleteMany(query);

        LOG.debug("removeMatchesWithPGroup: removed {} matches using {}.delete({})",
                  result.getDeletedCount(), MongoUtil.fullName(collection), query.toJson());
    }

    public void removeMatchesOutsideGroup(final MatchCollectionId collectionId,
                                          final String groupId)
            throws IllegalArgumentException, ObjectNotFoundException {

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
            throws IllegalArgumentException, ObjectNotFoundException {

        LOG.debug("removeAllMatches: entry, collectionId={}", collectionId);

        final MongoCollection<Document> collection = getExistingCollection(collectionId);

        collection.drop();
    }

    /**
     * Renames the specified match collection.
     *
     * @param  fromCollectionId  original match collection.
     * @param  toCollectionId    new match collection.
     *
     * @throws IllegalArgumentException
     *   if the new match collection already exists or
     *   the original match collection cannot be renamed for any other reason.
     *
     * @throws ObjectNotFoundException
     *   if the original match collection does not exist.
     */
    public void renameMatchCollection(final MatchCollectionId fromCollectionId,
                                      final MatchCollectionId toCollectionId)
            throws IllegalArgumentException, ObjectNotFoundException {

        final String fromCollectionName = fromCollectionId.getDbCollectionName();
        final boolean fromCollectionExists = MongoUtil.exists(matchDatabase, fromCollectionName);
        if (! fromCollectionExists) {
            throw new ObjectNotFoundException(fromCollectionId + " does not exist");
        }

        final String toCollectionName = toCollectionId.getDbCollectionName();
        final boolean toCollectionExists = MongoUtil.exists(matchDatabase, toCollectionName);
        if (toCollectionExists) {
            throw new IllegalArgumentException(toCollectionId + " already exists");
        }

        MongoUtil.renameCollection(matchDatabase, fromCollectionName, toCollectionName);
    }

    public MatchTrial getMatchTrial(final String trialId)
            throws IllegalArgumentException,
                   ObjectNotFoundException {

        MongoUtil.validateRequiredParameter("trialId", trialId);

        final MongoCollection<Document> collection = getMatchTrialCollection();

        final Document query = new Document();
        query.put("_id", new ObjectId(trialId));

        final Document document = collection.find(query).first();

        if (document == null) {
            throw new ObjectNotFoundException("match trial with id '" + trialId + "' does not exist in the " +
                                              MongoUtil.fullName(collection) + " collection");
        }

        return MatchTrial.fromJson(document.toJson());
    }

    public MatchTrial insertMatchTrial(final MatchTrial matchTrial)
            throws IllegalArgumentException {

        MongoUtil.validateRequiredParameter("matchTrial", matchTrial);

        final MongoCollection<Document> collection = getMatchTrialCollection();
        final MatchTrial copyWithNullId = matchTrial.getCopyWithId(null);
        final Document matchTrialDocument = Document.parse(copyWithNullId.toJson());

        collection.insertOne(matchTrialDocument);

        final ObjectId id = matchTrialDocument.getObjectId("_id");
        return matchTrial.getCopyWithId(String.valueOf(id));
    }

    public void updateMatchCountsForPGroup(final MatchCollectionId collectionId,
                                           final String pGroupId) {

        LOG.debug("updateMatchCountsForPGroup: entry, collectionId={}, pGroupId={}",
                  collectionId, pGroupId);

        final ProcessTimer timer = new ProcessTimer();

        final MongoCollection<Document> collection = getExistingCollection(collectionId);

        MongoUtil.validateRequiredParameter("pGroupId", pGroupId);

        // db.<matchCollection>.aggregate(
        //     [
        //         {
        //             "$match":  {
        //                 "pGroupId": "..."
        //             }
        //         },
        //         {
        //             "$project":  {
        //                 "_id": "$_id",
        //                 "matchCount": { "$size": "$matches.w" } } }
        //             }
        //         }
        //     ]
        // )

        final Document matchCriteria = new Document("pGroupId", pGroupId);
        final Document projectCriteria = new Document("_id", "$_id").append("matchCount",
                                                                           new Document("$size", "$matches.w"));

        final List<Document> pipeline = new ArrayList<>();
        pipeline.add(new Document("$match", matchCriteria));
        pipeline.add(new Document("$project", projectCriteria));

        final List<WriteModel<Document>> modelList = new ArrayList<>();

        try (final MongoCursor<Document> cursor = collection.aggregate(pipeline).iterator()) {
            while (cursor.hasNext()) {
                final Document result = cursor.next();
                final Document filter = new Document("_id", result.getObjectId("_id"));
                final Document update = new Document("$set",
                                                     new Document("matchCount",
                                                                  result.getInteger("matchCount")));
                modelList.add(new UpdateOneModel<>(filter, update));
            }
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("updateMatchCountsForPGroup: calculated match counts for {} pairs returned by {}.aggregate({})",
                      modelList.size(), MongoUtil.fullName(collection), MongoUtil.toJson(pipeline));
        }

        final BulkWriteResult result = collection.bulkWrite(modelList, MongoUtil.UNORDERED_OPTION);

        if (LOG.isDebugEnabled()) {
            final String bulkResultMessage = MongoUtil.toMessage("matches", result, modelList.size());
            LOG.debug("updateMatchCountsForPGroup: {} using {}.initializeUnorderedBulkOp(), elapsedMilliseconds={}",
                      bulkResultMessage, MongoUtil.fullName(collection), timer.getElapsedMilliseconds());
        }

    }
    
    private MongoCollection<Document> getMatchTrialCollection() {
        return matchDatabase.getCollection(MATCH_TRIAL_COLLECTION_NAME);
    }

    MongoCollection<Document> getExistingCollection(final MatchCollectionId collectionId) {
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

        try (final MongoCursor<String> cursor = collection.distinct(fieldName, String.class).iterator()) {
            while (cursor.hasNext()) {
                distinctIds.add(cursor.next());
            }
        }

        return distinctIds;
    }

    private Document getNormalizedGroupIdQuery(final String pGroupId,
                                               final String qGroupId) {
        final String noTileId = "";
        final CanvasMatches normalizedCriteria = new CanvasMatches(pGroupId, noTileId, qGroupId, noTileId, null);
        return new Document(
                "pGroupId", normalizedCriteria.getpGroupId()).append(
                "qGroupId", normalizedCriteria.getqGroupId());
    }

    private Document getNormalizedIdQuery(final String pGroupId,
                                          final String pId,
                                          final String qGroupId,
                                          final String qId) {
        final CanvasMatches normalizedCriteria = new CanvasMatches(pGroupId, pId, qGroupId, qId, null);
        return new Document(
                "pGroupId", normalizedCriteria.getpGroupId()).append(
                "pId", normalizedCriteria.getpId()).append(
                "qGroupId", normalizedCriteria.getqGroupId()).append(
                "qId", normalizedCriteria.getqId());
    }

    List<CanvasMatches> getMatches(final MongoCollection<Document> collection,
                                   final Document query,
                                   final boolean excludeMatchDetails) {

        final List<CanvasMatches> canvasMatchesList = new ArrayList<>();

        final Document projection = excludeMatchDetails ? EXCLUDE_MONGO_ID_KEY_AND_MATCHES : EXCLUDE_MONGO_ID_KEY;

        try (final MongoCursor<Document> cursor = collection.find(query).projection(projection).iterator()) {
            while (cursor.hasNext()) {
                canvasMatchesList.add(CanvasMatches.fromJson(cursor.next().toJson()));
            }
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("getMatches: {} matches returned for {}.find({},{})",
                      canvasMatchesList.size(), MongoUtil.fullName(collection), query.toJson(), projection.toJson());
        }

        return canvasMatchesList;
    }

    private void writeMatches(final List<MongoCollection<Document>> collectionList,
                              final Document query,
                              final boolean excludeMatchDetails,
                              final OutputStream outputStream)
            throws IOException {

        final Document projection = excludeMatchDetails ? EXCLUDE_MONGO_ID_KEY_AND_MATCHES : EXCLUDE_MONGO_ID_KEY;

        if (collectionList.size() > 1) {

            writeMergedMatches(collectionList, query, projection, outputStream);

        } else {

            final MongoCollection<Document> collection = collectionList.get(0);

            final ProcessTimer timer = new ProcessTimer();

            outputStream.write(OPEN_BRACKET);

            int count = 0;
            try (final MongoCursor<Document> cursor = collection.find(query).projection(projection).sort(MATCH_ORDER_BY).iterator()) {

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
                          count, MongoUtil.fullName(collection), query.toJson(), projection.toJson(), timer.getElapsedSeconds());
            }
        }
    }

    private void writeMergedMatches(final List<MongoCollection<Document>> collectionList,
                                    final Document query,
                                    final Document projection,
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
                cursorList.add(collection.find(query).projection(projection).sort(MATCH_ORDER_BY).iterator());
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
                      count, collectionNames, query.toJson(), projection.toJson(), MATCH_ORDER_BY_JSON, timer.getElapsedSeconds());
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

    private Set<String> getMultiConsensusGroupIds(final MatchCollectionId collectionId,
                                                  final boolean includeQGroupIds)
            throws IllegalArgumentException {

        final MongoCollection<Document> matchCollection = getExistingCollection(collectionId);

        final List<Document> pipeline = new ArrayList<>();
        pipeline.add(new Document("$match",
                                  new Document("consensusSetData",
                                               new Document(QueryOperators.EXISTS, true))));

        if (includeQGroupIds) {

            // db.<matchCollection>.aggregate(
            //     [
            //         { "$match": { "consensusSetData": { "$exists": true } } },
            //         { "$group": { "_id": { "pGroupId": "$pGroupId", "qGroupId": "$qGroupId" } } }
            //     ]
            // )

            pipeline.add(new Document("$group",
                                      new Document("_id",
                                                   new Document("pGroupId", "$pGroupId").
                                                           append("qGroupId", "$qGroupId"))));
        } else {

            // db.<matchCollection>.aggregate(
            //     [
            //         { "$match": { "consensusSetData": { "$exists": true } } },
            //         { "$group": { "_id": { "pGroupId": "$pGroupId" } } }
            //     ]
            // )

            pipeline.add(new Document("$group",
                                      new Document("_id",
                                                   new Document("pGroupId", "$pGroupId"))));
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("getMultiConsensusGroupIds: running {}.aggregate({})",
                      MongoUtil.fullName(matchCollection),
                      MongoUtil.toJson(pipeline));
        }

        // sort and reduce to distinct set of group ids here instead of in pipeline
        final TreeSet<String> groupIdsWithMultiplePairs = new TreeSet<>();

        // mongodb java 3.0 driver notes:
        // -- need to set cursor batchSize to prevent NPE from cursor creation
        final AggregateIterable<Document> iterable = matchCollection.aggregate(pipeline).batchSize(1);
        try (final MongoCursor<Document> cursor = iterable.iterator()) {
            while (cursor.hasNext()) {
                final Document id = cursor.next().get("_id", Document.class);
                groupIdsWithMultiplePairs.add(id.getString("pGroupId"));
                if (includeQGroupIds) {
                    groupIdsWithMultiplePairs.add(id.getString("qGroupId"));
                }
            }
        }

        return groupIdsWithMultiplePairs;
    }

    private Document getOutsideGroupQuery(final String groupId) {
        final List<Document> queryList = new ArrayList<>();
        queryList.add(new Document("pGroupId", groupId).append(
                "qGroupId", new Document(QueryOperators.NE, groupId)));
        queryList.add(new Document("qGroupId", groupId).append(
                "pGroupId", new Document(QueryOperators.NE, groupId)));
        return new Document(QueryOperators.OR, queryList);
    }

    private Document getInvolvingObjectQuery(final String groupId,final String id){
        final List<Document> queryList = new ArrayList<>();
        queryList.add(new Document("pGroupId", groupId).append(
                "pId", id));
        queryList.add(new Document("qGroupId", groupId).append(
                "qId", id));
        return new Document(QueryOperators.OR, queryList);
    }

    private Document getInvolvingObjectAndGroupQuery(final String groupId,final String id, final String qGroupId){
       final List<Document> queryList = new ArrayList<>();
         queryList.add(new Document("pGroupId", groupId).append(
                 "pId", id).append("qGroupId",qGroupId));
         queryList.add(new Document("qGroupId", groupId).append(
                 "qId", id).append("pGroupId",qGroupId));

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

    private static void validateRequiredCanvasIds(final String groupId,
                                                  final String id) {
        MongoUtil.validateRequiredParameter("groupId", groupId);
        MongoUtil.validateRequiredParameter("id", id);
    }

    private static void validateRequiredGroupIds(final String pGroupId,
                                                 final String qGroupId) {
        MongoUtil.validateRequiredParameter("pGroupId", pGroupId);
        MongoUtil.validateRequiredParameter("qGroupId", qGroupId);
    }

    private static void validateRequiredPairIds(final String pGroupId,
                                                final String pId,
                                                final String qGroupId,
                                                final String qId) {
        MongoUtil.validateRequiredParameter("pGroupId", pGroupId);
        MongoUtil.validateRequiredParameter("pId", pId);
        MongoUtil.validateRequiredParameter("qGroupId", qGroupId);
        MongoUtil.validateRequiredParameter("qId", qId);
    }

    private static final Logger LOG = LoggerFactory.getLogger(MatchDao.class);

    private static final Document MATCH_ORDER_BY =
            new Document("pGroupId", 1).append("qGroupId", 1).append("pId", 1).append("qId", 1);
    private static final String MATCH_ORDER_BY_JSON = MATCH_ORDER_BY.toJson();
    private static final Document EXCLUDE_MONGO_ID_KEY = new Document("_id", 0);
    private static final Document EXCLUDE_MONGO_ID_KEY_AND_MATCHES = new Document("_id", 0).append("matches", 0);
    private static final byte[] OPEN_BRACKET = "[".getBytes();
    private static final byte[] COMMA_WITH_NEW_LINE = ",\n".getBytes();
    private static final byte[] CLOSE_BRACKET = "]".getBytes();

    private static final IndexOptions MATCH_A_OPTIONS = new IndexOptions().unique(true).background(true).name("A");
    private static final IndexOptions MATCH_B_OPTIONS = new IndexOptions().background(true).name("B");

}

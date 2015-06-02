package org.janelia.render.service.dao;

import com.mongodb.AggregationOutput;
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
import com.mongodb.WriteConcern;
import com.mongodb.WriteResult;
import com.mongodb.util.JSON;

import java.io.IOException;
import java.io.OutputStream;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ListTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileCoordinates;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackStats;
import org.janelia.alignment.util.ProcessTimer;
import org.janelia.render.service.model.ObjectNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Data access object for Render database.
 *
 * @author Eric Trautman
 */
public class RenderDao {

    public static final String RENDER_DB_NAME = "render";
    public static final String STACK_META_DATA_COLLECTION_NAME = "admin__stack_meta_data";

    public static RenderDao build()
            throws UnknownHostException {
        final MongoClient mongoClient = SharedMongoClient.getInstance();
        return new RenderDao(mongoClient);
    }

    private final DB renderDb;

    public RenderDao(final MongoClient client) {
        renderDb = client.getDB(RENDER_DB_NAME);
    }

    /**
     * @return a render parameters object for all tiles that match the specified criteria.
     *
     * @throws IllegalArgumentException
     *   if any required parameters are missing or the stack cannot be found.
     */
    public RenderParameters getParameters(final StackId stackId,
                                          final String groupId,
                                          final Double x,
                                          final Double y,
                                          final Double z,
                                          final Integer width,
                                          final Integer height,
                                          final Double scale)
            throws IllegalArgumentException {

        validateRequiredParameter("stackId", stackId);
        validateRequiredParameter("x", x);
        validateRequiredParameter("y", y);
        validateRequiredParameter("z", z);
        validateRequiredParameter("width", width);
        validateRequiredParameter("height", height);
        validateRequiredParameter("scale", scale);

        final double lowerRightX = x + width;
        final double lowerRightY = y + height;
        final BasicDBObject tileQuery = getIntersectsBoxQuery(z, x, y, lowerRightX, lowerRightY);
        if (groupId != null) {
            tileQuery.append("groupId", groupId);
        }

        final RenderParameters renderParameters = new RenderParameters(null, x, y, width, height, scale);
        addResolvedTileSpecs(stackId, tileQuery, renderParameters);

        return renderParameters;
    }

    /**
     * @return number of tiles within the specified bounding box.
     *
     * @throws IllegalArgumentException
     *   if any required parameters are missing or the stack cannot be found.
     */
    public int getTileCount(final StackId stackId,
                            final Double x,
                            final Double y,
                            final Double z,
                            final Integer width,
                            final Integer height)
            throws IllegalArgumentException {

        validateRequiredParameter("stackId", stackId);
        validateRequiredParameter("x", x);
        validateRequiredParameter("y", y);
        validateRequiredParameter("z", z);
        validateRequiredParameter("width", width);
        validateRequiredParameter("height", height);

        final DBCollection tileCollection = getTileCollection(stackId);

        final double lowerRightX = x + width;
        final double lowerRightY = y + height;
        final DBObject tileQuery = getIntersectsBoxQuery(z, x, y, lowerRightX, lowerRightY);

        final int count = tileCollection.find(tileQuery).count();

        LOG.debug("getTileCount: found {} tile spec(s) for {}.{}.find({})",
                  count, RENDER_DB_NAME, tileCollection.getName(), tileQuery);

        return count;
    }

    /**
     * @return the specified tile spec.
     *
     * @throws IllegalArgumentException
     *   if any required parameters are missing.
     *
     * @throws ObjectNotFoundException
     *   if a spec with the specified z and tileId cannot be found.
     */
    public TileSpec getTileSpec(final StackId stackId,
                                final String tileId,
                                final boolean resolveTransformReferences)
            throws IllegalArgumentException,
                   ObjectNotFoundException {

        validateRequiredParameter("stackId", stackId);
        validateRequiredParameter("tileId", tileId);

        final DBCollection tileCollection = getTileCollection(stackId);

        final BasicDBObject query = new BasicDBObject();
        query.put("tileId", tileId);

        LOG.debug("getTileSpec: {}.{}.find({})",
                  tileCollection.getDB().getName(), tileCollection.getName(), query);

        // EXAMPLE:   find({ "tileId" : "140723171842050101.3299.0"})
        // INDEX:     tileId_1
        final DBObject document = tileCollection.findOne(query);

        if (document == null) {
            throw new ObjectNotFoundException("tile spec with id '" + tileId + "' does not exist in the " +
                                              tileCollection.getFullName() + " collection");
        }

        final TileSpec tileSpec = TileSpec.fromJson(document.toString());

        if (resolveTransformReferences) {
            resolveTransformReferencesForTiles(stackId, Arrays.asList(tileSpec));
        }

        return tileSpec;
    }

    public Map<String, TransformSpec> resolveTransformReferencesForTiles(final StackId stackId,
                                                                         final List<TileSpec> tileSpecs)
            throws IllegalStateException {

        final Set<String> unresolvedIds = new HashSet<>();
        ListTransformSpec transforms;
        for (final TileSpec tileSpec : tileSpecs) {
            transforms = tileSpec.getTransforms();
            if (transforms != null) {
                transforms.addUnresolvedIds(unresolvedIds);
            }
        }

        final Map<String, TransformSpec> resolvedIdToSpecMap = new HashMap<>();

        final int unresolvedCount = unresolvedIds.size();
        if (unresolvedCount > 0) {

            final DBCollection transformCollection = getTransformCollection(stackId);
            getDataForTransformSpecReferences(transformCollection, unresolvedIds, resolvedIdToSpecMap, 1);

            // resolve any references within the retrieved transform specs
            for (final TransformSpec transformSpec : resolvedIdToSpecMap.values()) {
                transformSpec.resolveReferences(resolvedIdToSpecMap);
            }

            // apply fully resolved transform specs to tiles
            for (final TileSpec tileSpec : tileSpecs) {
                transforms = tileSpec.getTransforms();
                transforms.resolveReferences(resolvedIdToSpecMap);
                if (! transforms.isFullyResolved()) {
                    throw new IllegalStateException("tile spec " + tileSpec.getTileId() +
                                                    " is not fully resolved after applying the following transform specs: " +
                                                    resolvedIdToSpecMap.keySet());
                }
            }

        }

        return resolvedIdToSpecMap;
    }


    /**
     * @return a list of resolved tile specifications for all tiles that encompass the specified coordinates.
     *
     * @throws IllegalArgumentException
     *   if any required parameters are missing, if the stack cannot be found, or
     *   if no tile can be found that encompasses the coordinates.
     */
    public List<TileSpec> getTileSpecs(final StackId stackId,
                                       final Double x,
                                       final Double y,
                                       final Double z)
            throws IllegalArgumentException {

        validateRequiredParameter("stackId", stackId);
        validateRequiredParameter("x", x);
        validateRequiredParameter("y", y);
        validateRequiredParameter("z", z);

        final DBObject tileQuery = getIntersectsBoxQuery(z, x, y, x, y);
        final RenderParameters renderParameters = new RenderParameters();
        addResolvedTileSpecs(stackId, tileQuery, renderParameters);

        if (! renderParameters.hasTileSpecs()) {
            throw new IllegalArgumentException("no tile specifications found in " + stackId +
                                               " for world coordinates x=" + x + ", y=" + y + ", z=" + z);
        }

        return renderParameters.getTileSpecs();
    }

    public void writeCoordinatesWithTileIds(final StackId stackId,
                                            final Double z,
                                            final List<TileCoordinates> worldCoordinatesList,
                                            final OutputStream outputStream)
            throws IllegalArgumentException, IOException {

        LOG.debug("writeCoordinatesWithTileIds: entry, stackId={}, z={}, worldCoordinatesList.size()={}",
                  stackId, z, worldCoordinatesList.size());

        validateRequiredParameter("stackId", stackId);
        validateRequiredParameter("z", z);

        final DBCollection tileCollection = getTileCollection(stackId);
        final DBObject tileKeys = new BasicDBObject("tileId", 1).append("_id", 0);

        // order tile specs by tileId to ensure consistent coordinate mapping
        final DBObject orderBy = new BasicDBObject("tileId", 1);

        final ProcessTimer timer = new ProcessTimer();
        final byte[] openBracket = "[".getBytes();
        final byte[] comma = ",".getBytes();
        final byte[] closeBracket = "]".getBytes();

        int coordinateCount = 0;

        double[] world;
        DBObject tileQuery = null;
        DBCursor cursor = null;
        DBObject document;
        Object tileId;
        String coordinatesJson;
        try {

            outputStream.write(openBracket);

            TileCoordinates worldCoordinates;
            for (int i = 0; i < worldCoordinatesList.size(); i++) {

                worldCoordinates = worldCoordinatesList.get(i);
                world = worldCoordinates.getWorld();

                if (world == null) {
                    throw new IllegalArgumentException("world values are missing for element " + i);
                } else if (world.length < 2) {
                    throw new IllegalArgumentException("world values must include both x and y for element " + i);
                }

                tileQuery = getIntersectsBoxQuery(z, world[0], world[1], world[0], world[1]);

                // EXAMPLE:   find({"z": 3299.0 , "minX": {"$lte": 95000.0}, "minY": {"$lte": 200000.0}, "maxX": {"$gte": 95000.0}, "maxY": {"$gte": 200000.0}}, {"tileId":1, "_id": 0}).sort({"tileId" : 1})
                // INDEXES:   z_1_minY_1_minX_1_maxY_1_maxX_1_tileId_1 (z1_minX_1, z1_maxX_1, ... used for edge cases)
                cursor = tileCollection.find(tileQuery, tileKeys);
                cursor.sort(orderBy);

                if (i > 0) {
                    outputStream.write(comma);
                }
                outputStream.write(openBracket);

                if (cursor.hasNext()) {

                    document = cursor.next();
                    tileId = document.get("tileId");
                    if (tileId != null) {
                        worldCoordinates.setTileId(tileId.toString());
                    }
                    coordinatesJson = worldCoordinates.toJson();
                    outputStream.write(coordinatesJson.getBytes());

                    while (cursor.hasNext()) {
                        document = cursor.next();
                        tileId = document.get("tileId");
                        if (tileId != null) {
                            worldCoordinates.setTileId(tileId.toString());
                        }
                        coordinatesJson = worldCoordinates.toJson();

                        outputStream.write(comma);
                        outputStream.write(coordinatesJson.getBytes());
                    }

                } else {

                    coordinatesJson = worldCoordinates.toJson();
                    outputStream.write(coordinatesJson.getBytes());

                }

                cursor.close();

                outputStream.write(closeBracket);

                coordinateCount++;

                if (timer.hasIntervalPassed()) {
                    LOG.debug("writeCoordinatesWithTileIds: data written for {} coordinates", coordinateCount);
                }
            }

            outputStream.write(closeBracket);

        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        LOG.debug("writeCoordinatesWithTileIds: wrote data for {} coordinates returned by queries like {}.find({},{}).sort({}), elapsedSeconds={}",
                  coordinateCount, tileCollection.getFullName(), tileQuery, tileKeys, orderBy, timer.getElapsedSeconds());
    }

    /**
     * @return a list of resolved tile specifications for all tiles that have the specified z.
     *
     * @throws IllegalArgumentException
     *   if any required parameters are missing or if the stack cannot be found, or
     *   if no tile can be found for the specified z.
     */
    public List<TileSpec> getTileSpecs(final StackId stackId,
                                       final Double z)
            throws IllegalArgumentException {

        validateRequiredParameter("stackId", stackId);
        validateRequiredParameter("z", z);

        final DBObject tileQuery = new BasicDBObject("z", z);
        final RenderParameters renderParameters = new RenderParameters();
        addResolvedTileSpecs(stackId, tileQuery, renderParameters);

        if (! renderParameters.hasTileSpecs()) {
            throw new IllegalArgumentException("no tile specifications found in " + stackId +" for z=" + z);
        }

        return renderParameters.getTileSpecs();
    }

    /**
     * @return a resolved tile spec collection for all tiles that have the specified z.
     *
     * @throws IllegalArgumentException
     *   if any required parameters are missing or if the stack cannot be found, or
     *   if no tile can be found for the specified z.
     */
    public ResolvedTileSpecCollection getResolvedTiles(final StackId stackId,
                                                       final Double z)
            throws IllegalArgumentException {

        validateRequiredParameter("stackId", stackId);
        validateRequiredParameter("z", z);

        final DBObject tileQuery = new BasicDBObject("z", z);
        final RenderParameters renderParameters = new RenderParameters();
        final Map<String, TransformSpec> resolvedIdToSpecMap = addResolvedTileSpecs(stackId,
                                                                                    tileQuery,
                                                                                    renderParameters);

        if (! renderParameters.hasTileSpecs()) {
            throw new IllegalArgumentException("no tile specifications found in " + stackId +" for z=" + z);
        }

        return new ResolvedTileSpecCollection(resolvedIdToSpecMap.values(),
                                              renderParameters.getTileSpecs());
    }

    /**
     * @return a resolved tile spec collection for all tiles that match the specified criteria.
     *
     * @throws IllegalArgumentException
     *   if any required parameters are missing or if the stack cannot be found, or
     *   if no tile can be found for the specified criteria.
     */
    public ResolvedTileSpecCollection getResolvedTiles(final StackId stackId,
                                                       final Double minZ,
                                                       final Double maxZ,
                                                       final String groupId,
                                                       final Double minX,
                                                       final Double maxX,
                                                       final Double minY,
                                                       final Double maxY)
            throws IllegalArgumentException {

        validateRequiredParameter("stackId", stackId);

        final BasicDBObject query = getGroupQuery(minZ, maxZ, groupId, minX, maxX, minY, maxY);
        final RenderParameters renderParameters = new RenderParameters();
        final Map<String, TransformSpec> resolvedIdToSpecMap = addResolvedTileSpecs(stackId,
                                                                                    query,
                                                                                    renderParameters);

        if (! renderParameters.hasTileSpecs()) {
            throw new IllegalArgumentException("no tile specifications found in " + stackId +" for " + query);
        }

        return new ResolvedTileSpecCollection(resolvedIdToSpecMap.values(),
                                              renderParameters.getTileSpecs());
    }

    /**
     * Saves the specified tile spec to the database.
     *
     * @param  stackId            stack identifier.
     * @param  resolvedTileSpecs  collection of resolved tile specs (with referenced transforms).
     *
     * @throws IllegalArgumentException
     *   if any required parameters or transform spec references are missing.
     */
    public void saveResolvedTiles(final StackId stackId,
                                  final ResolvedTileSpecCollection resolvedTileSpecs)
            throws IllegalArgumentException {

        validateRequiredParameter("stackId", stackId);
        validateRequiredParameter("resolvedTileSpecs", resolvedTileSpecs);

        final Collection<TransformSpec> transformSpecs = resolvedTileSpecs.getTransformSpecs();
        final Collection<TileSpec> tileSpecs = resolvedTileSpecs.getTileSpecs();

        if (transformSpecs.size() > 0) {

            final DBCollection transformCollection = getTransformCollection(stackId);
            final BulkWriteOperation bulk = transformCollection.initializeUnorderedBulkOperation();

            BasicDBObject query = null;
            DBObject transformSpecObject;
            for (final TransformSpec transformSpec : transformSpecs) {
                query = new BasicDBObject("id", transformSpec.getId());
                transformSpecObject = (DBObject) JSON.parse(transformSpec.toJson());
                bulk.find(query).upsert().replaceOne(transformSpecObject);
            }

            final BulkWriteResult result = bulk.execute();

            if (LOG.isDebugEnabled()) {
                final String bulkResultMessage = getBulkResultMessage("transform specs", result, transformSpecs.size());
                LOG.debug("saveResolvedTiles: {} using {}.initializeUnorderedBulkOp()",
                          bulkResultMessage, transformCollection.getFullName(), query);
            }

            // TODO: re-derive bounding boxes for all tiles (outside this collection) that reference modified transforms
        }

        if (tileSpecs.size() > 0) {

            final DBCollection tileCollection = getTileCollection(stackId);
            final BulkWriteOperation bulkTileOperation = tileCollection.initializeUnorderedBulkOperation();

            BasicDBObject query = null;
            DBObject tileSpecObject;
            for (final TileSpec tileSpec : tileSpecs) {
                query = new BasicDBObject("tileId", tileSpec.getTileId());
                tileSpecObject = (DBObject) JSON.parse(tileSpec.toJson());
                bulkTileOperation.find(query).upsert().replaceOne(tileSpecObject);
            }

            final BulkWriteResult result = bulkTileOperation.execute();

            if (LOG.isDebugEnabled()) {
                final String bulkResultMessage = getBulkResultMessage("tile specs", result, tileSpecs.size());
                LOG.debug("saveResolvedTiles: {} using {}.initializeUnorderedBulkOp()",
                          bulkResultMessage, tileCollection.getFullName(), query);
            }
        }

    }

    /**
     * Saves the specified tile spec to the database.
     *
     * @param  stackId    stack identifier.
     * @param  tileSpec   specification to be saved.
     *
     * @return the specification updated with any attributes that were modified by the save.
     *
     * @throws IllegalArgumentException
     *   if any required parameters or transform spec references are missing.
     */
    public TileSpec saveTileSpec(final StackId stackId,
                                 final TileSpec tileSpec)
            throws IllegalArgumentException {

        validateRequiredParameter("stackId", stackId);
        validateRequiredParameter("tileSpec", tileSpec);
        validateRequiredParameter("tileSpec.tileId", tileSpec.getTileId());

        final DBCollection tileCollection = getTileCollection(stackId);

        final String context = "tile spec with id '" + tileSpec.getTileId();
        validateTransformReferences(context, stackId, tileSpec.getTransforms());

        final BasicDBObject query = new BasicDBObject();
        query.put("tileId", tileSpec.getTileId());

        final DBObject tileSpecObject = (DBObject) JSON.parse(tileSpec.toJson());

        final WriteResult result = tileCollection.update(query, tileSpecObject, true, false);

        String action;
        if (result.isUpdateOfExisting()) {
            action = "update";
        } else {
            action = "insert";
        }

        LOG.debug("saveTileSpec: {}.{},({}), upsertedId is {}",
                  tileCollection.getFullName(), action, query, result.getUpsertedId());

        return tileSpec;
    }

    /**
     * @return the specified transform spec.
     *
     * @throws IllegalArgumentException
     *   if any required parameters are missing.
     *
     * @throws ObjectNotFoundException
     *   if a spec with the specified transformId cannot be found.
     */
    public TransformSpec getTransformSpec(final StackId stackId,
                                          final String transformId)
            throws IllegalArgumentException,
                   ObjectNotFoundException {

        validateRequiredParameter("stackId", stackId);
        validateRequiredParameter("transformId", transformId);

        final DBCollection transformCollection = getTransformCollection(stackId);

        final BasicDBObject query = new BasicDBObject();
        query.put("id", transformId);

        LOG.debug("getTransformSpec: {}.find({})", transformCollection.getFullName(), query);

        final DBObject document = transformCollection.findOne(query);

        if (document == null) {
            throw new ObjectNotFoundException("transform spec with id '" + transformId + "' does not exist in the " +
                                              transformCollection.getFullName() + " collection");
        }

        return JsonUtils.GSON.fromJson(document.toString(), TransformSpec.class);
    }

    /**
     * Saves the specified transform spec to the database.
     *
     * @param  stackId        stack identifier.
     * @param  transformSpec  specification to be saved.
     *
     * @return the specification updated with any attributes that were modified by the save.
     *
     * @throws IllegalArgumentException
     *   if any required parameters or transform spec references are missing.
     */
    public TransformSpec saveTransformSpec(final StackId stackId,
                                           final TransformSpec transformSpec)
            throws IllegalArgumentException {

        validateRequiredParameter("stackId", stackId);
        validateRequiredParameter("transformSpec", transformSpec);
        validateRequiredParameter("transformSpec.id", transformSpec.getId());

        final DBCollection transformCollection = getTransformCollection(stackId);

        final String context = "transform spec with id '" + transformSpec.getId() + "'";
        validateTransformReferences(context, stackId, transformSpec);

        final BasicDBObject query = new BasicDBObject();
        query.put("id", transformSpec.getId());

        final DBObject transformSpecObject = (DBObject) JSON.parse(transformSpec.toJson());

        final WriteResult result = transformCollection.update(query, transformSpecObject, true, false);

        String action;
        if (result.isUpdateOfExisting()) {
            action = "update";
        } else {
            action = "insert";
        }

        LOG.debug("saveTransformSpec: {}.{},({}), upsertedId is {}",
                  transformCollection.getFullName(), action, query, result.getUpsertedId());

        return transformSpec;
    }

    /**
     * @return list of distinct z values (layers) for the specified stackId.
     *
     * @throws IllegalArgumentException
     *   if any required parameters are missing or the stack cannot be found.
     */
    public List<Double> getZValues(final StackId stackId)
            throws IllegalArgumentException {

        validateRequiredParameter("stackId", stackId);

        final DBCollection tileCollection = getTileCollection(stackId);

        final List<Double> list = new ArrayList<>();
        for (final Object zValue : tileCollection.distinct("z")) {
            if (zValue != null) {
                list.add(new Double(zValue.toString()));
            }
        }

        LOG.debug("getZValues: returning {} values for {}", list.size(), tileCollection.getFullName());

        return list;
    }

    /**
     * @return list of stack meta data objects for the specified owner.
     *
     * @throws IllegalArgumentException
     *   if required parameters are not specified.
     */
    public List<StackMetaData> getStackMetaDataListForOwner(final String owner)
            throws IllegalArgumentException {

        validateRequiredParameter("owner", owner);

        List<StackMetaData> list = new ArrayList<>();

        final DBCollection stackMetaDataCollection = getStackMetaDataCollection();
        final BasicDBObject query = new BasicDBObject("stackId.owner", owner);
        final BasicDBObject sortCriteria = new BasicDBObject(
                "stackId.project", 1).append(
                "currentVersion.cycleNumber", 1).append(
                "currentVersion.cycleStepNumber", 1).append(
                "stackId.name", 1);

        try (DBCursor cursor = stackMetaDataCollection.find(query).sort(sortCriteria)) {
            DBObject document;
            while (cursor.hasNext()) {
                document = cursor.next();
                list.add(StackMetaData.fromJson(document.toString()));
            }
        }

        LOG.debug("getStackMetaDataListForOwner: returning {} values for {}.find({}).sort({})",
                  list.size(), stackMetaDataCollection.getFullName(), query, sortCriteria);

        return list;
    }

    /**
     * @return meta data for the specified stack or null if the stack cannot be found.
     *
     * @throws IllegalArgumentException
     *   if required parameters are not specified.
     */
    public StackMetaData getStackMetaData(final StackId stackId)
            throws IllegalArgumentException {

        validateRequiredParameter("stackId", stackId);

        StackMetaData stackMetaData = null;

        final DBCollection stackMetaDataCollection = getStackMetaDataCollection();
        final BasicDBObject query = getStackIdQuery(stackId);

        final DBObject document = stackMetaDataCollection.findOne(query);
        if (document != null) {
            stackMetaData = StackMetaData.fromJson(document.toString());
        }

        return stackMetaData;
    }

    public void saveStackMetaData(final StackMetaData stackMetaData) {

        LOG.debug("saveStackMetaData: entry, stackMetaData={}", stackMetaData);

        validateRequiredParameter("stackMetaData", stackMetaData);

        final StackId stackId = stackMetaData.getStackId();
        final DBCollection stackMetaDataCollection = getStackMetaDataCollection();

        final BasicDBObject query = getStackIdQuery(stackId);

        final DBObject stackMetaDataObject = (DBObject) JSON.parse(stackMetaData.toJson());

        final WriteResult result = stackMetaDataCollection.update(query, stackMetaDataObject, true, false);

        String action;
        if (result.isUpdateOfExisting()) {
            action = "update";
        } else {
            action = "insert";
            ensureCoreTransformIndex(getTransformCollection(stackId));
            ensureCoreTileIndex(getTileCollection(stackId));
        }

        LOG.debug("saveStackMetaData: {}.{}({})",
                  stackMetaDataCollection.getFullName(), action, query);
    }

    public StackMetaData ensureIndexesAndDeriveStats(final StackMetaData stackMetaData) {

        validateRequiredParameter("stackMetaData", stackMetaData);

        final StackId stackId = stackMetaData.getStackId();

        LOG.debug("ensureIndexesAndDeriveStats: entry, {}", stackId);

        final DBCollection transformCollection = getTransformCollection(stackId);
        final DBCollection tileCollection = getTileCollection(stackId);

        // should not be necessary, but okay to ensure core indexes just in case
        ensureCoreTransformIndex(transformCollection);
        ensureCoreTileIndex(tileCollection);

        ensureSupplementaryTileIndexes(tileCollection);

        final List<Double> zValues = getZValues(stackId);
        final long sectionCount = zValues.size();

        long nonIntegralSectionCount = 0;
        double truncatedZ;
        for (Double z : zValues) {
            truncatedZ = (double) z.intValue();
            if (z > truncatedZ) {
                nonIntegralSectionCount++;
            }
        }

        final long tileCount = tileCollection.count();
        LOG.debug("ensureIndexesAndDeriveStats: tileCount for {} is {}", stackId, tileCount);

        final long transformCount = transformCollection.count();
        LOG.debug("ensureIndexesAndDeriveStats: transformCount for {} is {}, deriving aggregate stats ...",
                  stackId, transformCount);

        // db.<stack_prefix>__tile.aggregate(
        //     [
        //         {
        //             "$project":  {
        //                 "z": "$z",
        //                 "minX": "$minX",
        //                 "minY": "$minY",
        //                 "maxX": "$maxX",
        //                 "maxY": "$maxY",
        //                 "width":  { "$subtract": [ "$maxX", "$minX" ] },
        //                 "height": { "$subtract": [ "$maxY", "$minY" ] }
        //             }
        //         },
        //         {
        //             "$group": {
        //                 "_id": "minAndMaxValues",
        //                 "stackMinX": { "$min": "$minX" },
        //                 "stackMinY": { "$min": "$minY" },
        //                 "stackMinZ": { "$min": "$z" },
        //                 "stackMaxX": { "$max": "$maxX" },
        //                 "stackMaxY": { "$max": "$maxY" },
        //                 "stackMaxZ": { "$max": "$z" } } } )
        //                 "stackMinTileWidth":  { "$min": "$width" },
        //                 "stackMaxTileWidth":  { "$max": "$width" },
        //                 "stackMinTileHeight": { "$min": "$height" },
        //                 "stackMaxTileHeight": { "$max": "$height" }
        //             }
        //         }
        //     ]
        // )

        final DBObject tileWidth = new BasicDBObject("$subtract", buildBasicDBList(new String[] {"$maxX","$minX" }));
        final DBObject tileHeight = new BasicDBObject("$subtract", buildBasicDBList(new String[] {"$maxY","$minY" }));
        final DBObject tileValues = new BasicDBObject("z", "$z").append(
                "minX", "$minX").append("minY", "$minY").append("maxX", "$maxX").append("maxY", "$maxY").append(
                "width", tileWidth).append("height", tileHeight);

        final DBObject projectStage = new BasicDBObject("$project", tileValues);

        final String minXKey = "stackMinX";
        final String minYKey = "stackMinY";
        final String minZKey = "stackMinZ";
        final String maxXKey = "stackMaxX";
        final String maxYKey = "stackMaxY";
        final String maxZKey = "stackMaxZ";
        final String minWidthKey = "stackMinTileWidth";
        final String maxWidthKey = "stackMaxTileWidth";
        final String minHeightKey = "stackMinTileHeight";
        final String maxHeightKey = "stackMaxTileHeight";

        final BasicDBObject minAndMaxValues = new BasicDBObject("_id", "minAndMaxValues");
        minAndMaxValues.append(minXKey, new BasicDBObject(QueryOperators.MIN, "$minX"));
        minAndMaxValues.append(minYKey, new BasicDBObject(QueryOperators.MIN, "$minY"));
        minAndMaxValues.append(minZKey, new BasicDBObject(QueryOperators.MIN, "$z"));
        minAndMaxValues.append(maxXKey, new BasicDBObject(QueryOperators.MAX, "$maxX"));
        minAndMaxValues.append(maxYKey, new BasicDBObject(QueryOperators.MAX, "$maxY"));
        minAndMaxValues.append(maxZKey, new BasicDBObject(QueryOperators.MAX, "$z"));
        minAndMaxValues.append(minWidthKey, new BasicDBObject(QueryOperators.MIN, "$width"));
        minAndMaxValues.append(maxWidthKey, new BasicDBObject(QueryOperators.MAX, "$width"));
        minAndMaxValues.append(minHeightKey, new BasicDBObject(QueryOperators.MIN, "$height"));
        minAndMaxValues.append(maxHeightKey, new BasicDBObject(QueryOperators.MAX, "$height"));

        final DBObject groupStage = new BasicDBObject("$group", minAndMaxValues);

        final List<DBObject> pipeline = new ArrayList<>();
        pipeline.add(projectStage);
        pipeline.add(groupStage);

        final AggregationOutput aggregationOutput = tileCollection.aggregate(pipeline);

        StackStats stats = null;
        for (DBObject result : aggregationOutput.results()) {

            if (stats != null) {
                throw new IllegalStateException("multiple aggregation results returned for " + pipeline);
            }

            final Bounds stackBounds = new Bounds(new Double(result.get(minXKey).toString()),
                                                  new Double(result.get(minYKey).toString()),
                                                  new Double(result.get(minZKey).toString()),
                                                  new Double(result.get(maxXKey).toString()),
                                                  new Double(result.get(maxYKey).toString()),
                                                  new Double(result.get(maxZKey).toString()));

            final Double minTileWidth = new Double(result.get(minWidthKey).toString());
            final Double maxTileWidth = new Double(result.get(maxWidthKey).toString());
            final Double minTileHeight = new Double(result.get(minHeightKey).toString());
            final Double maxTileHeight = new Double(result.get(maxHeightKey).toString());

            stats = new StackStats(stackBounds,
                                   sectionCount,
                                   nonIntegralSectionCount,
                                   tileCount,
                                   transformCount,
                                   minTileWidth.intValue(),
                                   maxTileWidth.intValue(),
                                   minTileHeight.intValue(),
                                   maxTileHeight.intValue());
        }

        if (stats == null) {
            throw new IllegalStateException("no aggregation results returned for " + pipeline);
        }

        stackMetaData.setStats(stats);

        LOG.debug("ensureIndexesAndDeriveStats: completed stat derivation for {}, stats={}", stackId, stats);

        stackMetaData.setState(StackMetaData.StackState.COMPLETE);

        final DBCollection stackMetaDataCollection = getStackMetaDataCollection();
        final BasicDBObject query = getStackIdQuery(stackId);
        final DBObject stackMetaDataObject = (DBObject) JSON.parse(stackMetaData.toJson());
        final WriteResult result = stackMetaDataCollection.update(query, stackMetaDataObject, true, false);

        String action;
        if (result.isUpdateOfExisting()) {
            action = "update";
        } else {
            action = "insert";
        }

        LOG.debug("ensureIndexesAndDeriveStats: {}.{}({})",
                  stackMetaDataCollection.getFullName(), action, query);

        return stackMetaData;
    }

    public void removeStack(final StackId stackId,
                            final boolean includeMetaData)
            throws IllegalArgumentException {

        validateRequiredParameter("stackId", stackId);

        final DBCollection tileCollection = getTileCollection(stackId);
        final long tileCount = tileCollection.getCount();
        tileCollection.drop();

        LOG.debug("removeStack: {}.drop() deleted {} document(s)",
                  tileCollection.getFullName(), tileCount);

        final DBCollection transformCollection = getTransformCollection(stackId);
        final long transformCount = transformCollection.getCount();
        transformCollection.drop();

        LOG.debug("removeStack: {}.drop() deleted {} document(s)",
                  transformCollection.getFullName(), transformCount);

        if (includeMetaData) {
            final DBCollection stackMetaDataCollection = getStackMetaDataCollection();
            final BasicDBObject stackIdQuery = getStackIdQuery(stackId);
            final WriteResult stackMetaDataRemoveResult = stackMetaDataCollection.remove(stackIdQuery);

            LOG.debug("removeStack: {}.remove({}) deleted {} document(s)",
                      stackMetaDataCollection.getFullName(), stackIdQuery, stackMetaDataRemoveResult.getN());
        }
    }

    public void removeSection(final StackId stackId,
                              final Double z)
            throws IllegalArgumentException {

        validateRequiredParameter("stackId", stackId);
        validateRequiredParameter("z", z);

        final DBCollection tileCollection = getTileCollection(stackId);
        final BasicDBObject tileQuery = new BasicDBObject("z", z);
        final WriteResult removeResult = tileCollection.remove(tileQuery);

        LOG.debug("removeSection: {}.remove({}) deleted {} document(s)",
                  tileCollection.getFullName(), tileQuery, removeResult.getN());
    }

    /**
     * @return coordinate bounds for all tiles in the specified stack layer.
     *
     * @throws IllegalArgumentException
     *   if any required parameters are missing or the stack cannot be found.
     */
    public Bounds getLayerBounds(final StackId stackId,
                                 final Double z)
            throws IllegalArgumentException {

        validateRequiredParameter("stackId", stackId);
        validateRequiredParameter("z", z);

        final DBCollection tileCollection = getTileCollection(stackId);
        final BasicDBObject tileQuery = new BasicDBObject("z", z);

        final Double minX = getBound(tileCollection, tileQuery, "minX", true);

        if (minX == null) {
            throw new IllegalArgumentException("stack " + stackId.getStack() +
                                               " does not contain any tiles with a z value of " + z);
        }

        final Double minY = getBound(tileCollection, tileQuery, "minY", true);
        final Double maxX = getBound(tileCollection, tileQuery, "maxX", false);
        final Double maxY = getBound(tileCollection, tileQuery, "maxY", false);

        return new Bounds(minX, minY, z, maxX, maxY, z);
    }

    /**
     * @return spatial data for all tiles in the specified stack layer.
     *
     * @throws IllegalArgumentException
     *   if any required parameters are missing or the stack cannot be found.
     */
    public List<TileBounds> getTileBounds(final StackId stackId,
                                          final Double z)
            throws IllegalArgumentException {

        validateRequiredParameter("stackId", stackId);
        validateRequiredParameter("z", z);

        final DBCollection tileCollection = getTileCollection(stackId);

        // EXAMPLE:   find({"z" : 3466.0},{"tileId": 1, "minX": 1, "minY": 1, "maxX": 1, "maxY": 1, "_id": 0})
        // INDEX:     z_1_minY_1_minX_1_maxY_1_maxX_1_tileId_1
        final DBObject tileQuery = new BasicDBObject("z", z);
        final DBObject tileKeys =
                new BasicDBObject("tileId", 1).append(
                        "minX", 1).append("minY", 1).append("maxX", 1).append("maxY", 1).append("_id", 0);

        final List<TileBounds> list = new ArrayList<>();

        try (DBCursor cursor = tileCollection.find(tileQuery, tileKeys)) {
            DBObject document;
            while (cursor.hasNext()) {
                document = cursor.next();
                list.add(TileBounds.fromJson(document.toString()));
            }
        }

        LOG.debug("getTileBounds: found {} tile spec(s) for {}.find({},{})",
                  list.size(), tileCollection.getFullName(), tileQuery, tileKeys);

        return list;
    }

    public void cloneStack(final StackId fromStackId,
                           final StackId toStackId)
            throws IllegalArgumentException, IllegalStateException {

        validateRequiredParameter("fromStackId", fromStackId);
        validateRequiredParameter("toStackId", toStackId);

        final DBCollection fromTransformCollection = getTransformCollection(fromStackId);
        final DBCollection toTransformCollection = getTransformCollection(toStackId);
        cloneCollection(fromTransformCollection, toTransformCollection);

        final DBCollection fromTileCollection = getTileCollection(fromStackId);
        final DBCollection toTileCollection = getTileCollection(toStackId);
        cloneCollection(fromTileCollection, toTileCollection);
    }

    /**
     * Writes the layout file data for the specified stack to the specified stream.
     *
     * @param  stackId          stack identifier.
     * @param  stackRequestUri  the base stack request URI for building tile render-parameter URIs.
     * @param  minZ             the minimum z to include (or null if no minimum).
     * @param  maxZ             the maximum z to include (or null if no maximum).
     * @param  outputStream     stream to which layout file data is to be written.
     *
     * @throws IllegalArgumentException
     *   if any required parameters are missing or the stack cannot be found.
     *
     * @throws IOException
     *   if the data cannot be written for any reason.
     */
    public void writeLayoutFileData(final StackId stackId,
                                    final String stackRequestUri,
                                    final Double minZ,
                                    final Double maxZ,
                                    final OutputStream outputStream)
            throws IllegalArgumentException, IOException {

        LOG.debug("writeLayoutFileData: entry, stackId={}, minZ={}, maxZ={}",
                  stackId, minZ, maxZ);

        validateRequiredParameter("stackId", stackId);

        final DBCollection tileCollection = getTileCollection(stackId);

        BasicDBObject zFilter = null;
        if (minZ != null) {
            zFilter = new BasicDBObject(QueryOperators.GTE, minZ);
            if (maxZ != null) {
                zFilter = zFilter.append(QueryOperators.LTE, maxZ);
            }
        } else if (maxZ != null) {
            zFilter = new BasicDBObject(QueryOperators.LTE, maxZ);
        }

        // EXAMPLE:   find({"z": {"$gte": 4370.0, "$lte": 4370.0}}, {"tileId": 1, "z": 1, "minX": 1, "minY": 1, "layout": 1, "mipmapLevels": 1}).sort({"z": 1, "minY": 1, "minX": 1})
        // INDEX:     z_1_minY_1_minX_1_maxY_1_maxX_1_tileId_1

        BasicDBObject tileQuery;
        if (zFilter == null) {
            tileQuery = new BasicDBObject();
        } else {
            tileQuery = new BasicDBObject("z", zFilter);
        }

        final DBObject tileKeys =
                new BasicDBObject("tileId", 1).append("z", 1).append("minX", 1).append("minY", 1).append("layout", 1).append("mipmapLevels", 1);

        final ProcessTimer timer = new ProcessTimer();
        int tileSpecCount = 0;
        final DBObject orderBy = new BasicDBObject("z", 1).append("minY", 1).append("minX", 1);
        try (DBCursor cursor = tileCollection.find(tileQuery, tileKeys)) {
            final String baseUriString = '\t' + stackRequestUri + "/tile/";

            cursor.sort(orderBy);

            DBObject document;
            TileSpec tileSpec;
            String layoutData;
            String uriString;
            while (cursor.hasNext()) {
                document = cursor.next();
                tileSpec = TileSpec.fromJson(document.toString());
                layoutData = tileSpec.toLayoutFileFormat();
                outputStream.write(layoutData.getBytes());

                // {stackRequestUri}/tile/{tileId}/render-parameters
                uriString = baseUriString + tileSpec.getTileId() + "/render-parameters" + "\n";
                outputStream.write(uriString.getBytes());
                tileSpecCount++;

                if (timer.hasIntervalPassed()) {
                    LOG.debug("writeLayoutFileData: data written for {} tiles", tileSpecCount);
                }

            }
        }

        LOG.debug("writeLayoutFileData: wrote data for {} tile spec(s) returned by {}.find({},{}).sort({}), elapsedSeconds={}",
                  tileSpecCount, tileCollection.getFullName(), tileQuery, tileKeys, orderBy, timer.getElapsedSeconds());
    }

    private List<TransformSpec> getTransformSpecs(final DBCollection transformCollection,
                                                  final Set<String> specIds) {
        final int specCount = specIds.size();
        final List<TransformSpec> transformSpecList = new ArrayList<>(specCount);
        if (specCount > 0) {

            final BasicDBObject transformQuery = new BasicDBObject();
            transformQuery.put("id", new BasicDBObject(QueryOperators.IN, specIds));

            LOG.debug("getTransformSpecs: {}.find({})", transformCollection.getFullName(), transformQuery);

            try (DBCursor cursor = transformCollection.find(transformQuery)) {
                DBObject document;
                TransformSpec transformSpec;
                while (cursor.hasNext()) {
                    document = cursor.next();
                    transformSpec = JsonUtils.GSON.fromJson(document.toString(), TransformSpec.class);
                    transformSpecList.add(transformSpec);
                }
            }

        }

        return transformSpecList;
    }

    private void getDataForTransformSpecReferences(final DBCollection transformCollection,
                                                   final Set<String> unresolvedSpecIds,
                                                   final Map<String, TransformSpec> resolvedIdToSpecMap,
                                                   final int callCount) {

        if (callCount > 10) {
            throw new IllegalStateException(callCount + " passes have been made to resolve transform references, " +
                                            "exiting in case the data is overly nested or there is a recursion error");
        }

        final int specCount = unresolvedSpecIds.size();
        if (specCount > 0) {

            final List<TransformSpec> transformSpecList = getTransformSpecs(transformCollection,
                                                                            unresolvedSpecIds);

            LOG.debug("resolveTransformSpecReferences: on pass {} retrieved {} transform specs",
                      callCount, transformSpecList.size());

            final Set<String> newlyUnresolvedSpecIds = new HashSet<>();

            for (final TransformSpec spec : transformSpecList) {
                resolvedIdToSpecMap.put(spec.getId(), spec);
                for (final String id : spec.getUnresolvedIds()) {
                    if ((! resolvedIdToSpecMap.containsKey(id)) && (! unresolvedSpecIds.contains(id))) {
                        newlyUnresolvedSpecIds.add(id);
                    }
                }
            }

            if (newlyUnresolvedSpecIds.size() > 0) {
                getDataForTransformSpecReferences(transformCollection,
                                                  newlyUnresolvedSpecIds,
                                                  resolvedIdToSpecMap,
                                                  (callCount + 1));
            }
        }
    }

    private Map<String, TransformSpec> addResolvedTileSpecs(final StackId stackId,
                                                            final DBObject tileQuery,
                                                            final RenderParameters renderParameters) {
        final DBCollection tileCollection = getTileCollection(stackId);

        // EXAMPLE:   find({"z": 4050.0 , "minX": {"$lte": 239850.0} , "minY": {"$lte": 149074.0}, "maxX": {"$gte": -109.0}, "maxY": {"$gte": 370.0}}).sort({"tileId": 1})
        // INDEXES:   z_1_minY_1_minX_1_maxY_1_maxX_1_tileId_1 (z1_minX_1, z1_maxX_1, ... used for edge cases)
        final DBCursor cursor = tileCollection.find(tileQuery);

        // order tile specs by tileId to ensure consistent coordinate mapping
        final DBObject orderBy = new BasicDBObject("tileId", 1);
        cursor.sort(orderBy);
        try {
            DBObject document;
            TileSpec tileSpec;
            int count = 0;
            while (cursor.hasNext()) {
                if (count > 50000) {
                    throw new IllegalArgumentException("query too broad, over " + count + " tiles match " + tileQuery);
                }
                document = cursor.next();
                tileSpec = TileSpec.fromJson(document.toString());
                renderParameters.addTileSpec(tileSpec);
                count++;
            }
        } finally {
            cursor.close();
        }

        LOG.debug("addResolvedTileSpecs: found {} tile spec(s) for {}.find({}).sort({})",
                  renderParameters.numberOfTileSpecs(), tileCollection.getFullName(), tileQuery, orderBy);

        return resolveTransformReferencesForTiles(stackId, renderParameters.getTileSpecs());
    }

    private DBObject lte(final double value) {
        return new BasicDBObject(QueryOperators.LTE, value);
    }

    private DBObject gte(final double value) {
        return new BasicDBObject(QueryOperators.GTE, value);
    }

    private BasicDBObject getIntersectsBoxQuery(final double z,
                                                final double x,
                                                final double y,
                                                final double lowerRightX,
                                                final double lowerRightY) {
        // intersection logic stolen from java.awt.Rectangle#intersects (without overflow checks)
        //   rx => minX, ry => minY, rw => maxX,        rh => maxY
        //   tx => x,    ty => y,    tw => lowerRightX, th => lowerRightY
        return new BasicDBObject("z", z).append(
                "minX", lte(lowerRightX)).append(
                "minY", lte(lowerRightY)).append(
                "maxX", gte(x)).append(
                "maxY", gte(y));
    }

    private BasicDBObject getStackIdQuery(StackId stackId) {
        return new BasicDBObject(
                "stackId.owner", stackId.getOwner()).append(
                "stackId.project", stackId.getProject()).append(
                "stackId.stack", stackId.getStack());
    }

    private BasicDBObject getGroupQuery(final Double minZ,
                                        final Double maxZ,
                                        final String groupId,
                                        final Double minX,
                                        final Double maxX,
                                        final Double minY,
                                        final Double maxY)
            throws IllegalArgumentException {

        final BasicDBObject groupQuery = new BasicDBObject();

        if ((minZ != null) && minZ.equals(maxZ)) {
            groupQuery.append("z", minZ);
        } else {
            if (minZ != null) {
                groupQuery.append("z", new BasicDBObject(QueryOperators.GTE, minZ));
            }
            if (maxZ != null) {
                groupQuery.append("z", new BasicDBObject(QueryOperators.LTE, maxZ));
            }
        }

        if (groupId != null) {
            groupQuery.append("groupId", groupId);
        }

        if (minX != null) {
            groupQuery.append("minX", new BasicDBObject(QueryOperators.GTE, minX));
        }
        if (maxX != null) {
            groupQuery.append("maxX", new BasicDBObject(QueryOperators.LTE, maxX));
        }
        if (minY != null) {
            groupQuery.append("minY", new BasicDBObject(QueryOperators.GTE, minY));
        }
        if (maxY != null) {
            groupQuery.append("maxY", new BasicDBObject(QueryOperators.LTE, maxY));
        }

        return groupQuery;
    }

    private BasicDBList buildBasicDBList(String[] values) {
        final BasicDBList list = new BasicDBList();
        Collections.addAll(list, values);
        return list;
    }

    private void validateTransformReferences(final String context,
                                             final StackId stackId,
                                             final TransformSpec transformSpec) {

        final Set<String> unresolvedTransformSpecIds = transformSpec.getUnresolvedIds();

        if (unresolvedTransformSpecIds.size() > 0) {
            final DBCollection transformCollection = getTransformCollection(stackId);
            final List<TransformSpec> transformSpecList = getTransformSpecs(transformCollection,
                                                                            unresolvedTransformSpecIds);
            if (transformSpecList.size() != unresolvedTransformSpecIds.size()) {
                final Set<String> existingIds = new HashSet<>(transformSpecList.size());
                for (final TransformSpec existingTransformSpec : transformSpecList) {
                    existingIds.add(existingTransformSpec.getId());
                }
                final Set<String> missingIds = new TreeSet<>();
                for (final String id : unresolvedTransformSpecIds) {
                    if (! existingIds.contains(id)) {
                        missingIds.add(id);
                    }
                }
                throw new IllegalArgumentException(context + " references transform id(s) " + missingIds +
                                                   " which do not exist in the " +
                                                   transformCollection.getFullName() + " collection");
            }
        }
    }

    private Double getBound(final DBCollection tileCollection,
                            final BasicDBObject tileQuery,
                            final String boundKey,
                            final boolean isMin) {

        Double bound = null;

        final BasicDBObject query = (BasicDBObject) tileQuery.copy();

        // Add a $gt / $lt constraint to ensure that null values aren't included and
        // that an indexOnly query is possible ($exists is not sufficient).
        int order = -1;
        if (isMin) {
            order = 1;
            // Double.MIN_VALUE is the minimum positive double value, so we need to use -Double.MAX_VALUE here
            query.append(boundKey, new BasicDBObject("$gt", -Double.MAX_VALUE));
        } else {
            query.append(boundKey, new BasicDBObject("$lt", Double.MAX_VALUE));
        }

        final DBObject tileKeys = new BasicDBObject(boundKey, 1).append("_id", 0);
        final DBObject orderBy = new BasicDBObject(boundKey, order);

        // EXAMPLE:   find({ "z" : 3299.0},{ "minX" : 1 , "_id" : 0}).sort({ "minX" : 1}).limit(1)
        // INDEXES:   z_1_minX_1, z_1_minY_1, z_1_maxX_1, z_1_maxY_1
        final DBCursor cursor = tileCollection.find(query, tileKeys);
        cursor.sort(orderBy).limit(1);
        try {
            DBObject document;
            if (cursor.hasNext()) {
                document = cursor.next();
                bound = (Double) document.get(boundKey);
            }
        } finally {
            cursor.close();
        }

        LOG.debug("getBound: returning {} for {}.{}.find({},{}).sort({}).limit(1)",
                  bound, RENDER_DB_NAME, tileCollection.getName(), query, tileKeys, orderBy);

        return bound;
    }

    private void cloneCollection(final DBCollection fromCollection,
                                 final DBCollection toCollection)
            throws IllegalStateException {

        final long fromCount = fromCollection.count();
        long toCount;
        final String fromFullName = fromCollection.getFullName();
        final String toFullName = toCollection.getFullName();

        LOG.debug("cloneCollection: entry, copying {} documents in {} to {}",
                  fromCount, fromFullName, toFullName);

        final ProcessTimer timer = new ProcessTimer(15000);

        // We use bulk inserts to improve performance, but we still need to chunk
        // the bulk operations to avoid memory issues with large collections.
        // This 10,000 document chunk size is arbitrary but seems to be sufficient.
        final int maxDocumentsPerBulkInsert = 10000;

        long count = 0;
        final DBObject query = new BasicDBObject();
        BulkWriteOperation bulk = toCollection.initializeOrderedBulkOperation();

        try (DBCursor cursor = fromCollection.find(query)) {

            BulkWriteResult result;
            long insertedCount;
            DBObject document;
            while (cursor.hasNext()) {
                document = cursor.next();
                bulk.insert(document);
                count++;
                if (count % maxDocumentsPerBulkInsert == 0) {
                    result = bulk.execute(WriteConcern.ACKNOWLEDGED);
                    insertedCount = result.getInsertedCount();
                    if (insertedCount != maxDocumentsPerBulkInsert) {
                        throw new IllegalStateException("only inserted " + insertedCount + " out of " +
                                                        maxDocumentsPerBulkInsert +
                                                        " documents for batch ending with document " + count);
                    }
                    bulk = toCollection.initializeOrderedBulkOperation();
                    if (timer.hasIntervalPassed()) {
                        LOG.debug("cloneCollection: inserted {} documents", count);
                    }
                }
            }

            if (count % maxDocumentsPerBulkInsert > 0) {
                result = bulk.execute(WriteConcern.ACKNOWLEDGED);
                insertedCount = result.getInsertedCount();
                final long expectedCount = count % maxDocumentsPerBulkInsert;
                if (insertedCount != expectedCount) {
                    throw new IllegalStateException("only inserted " + insertedCount + " out of " +
                                                    expectedCount +
                                                    " documents for last batch ending with document " + count);
                }
            }

            toCount = toCollection.count();

            if (toCount != fromCount) {
                throw new IllegalStateException("only inserted " + toCount + " out of " + fromCount + " documents");
            }

        }

        LOG.debug("cloneCollection: inserted {} documents from {}.find(\\{}) to {}",
                  toCount, fromFullName, toFullName);
    }

    private DBCollection getStackMetaDataCollection() {
        return renderDb.getCollection(STACK_META_DATA_COLLECTION_NAME);
    }

    private DBCollection getTileCollection(final StackId stackId) {
        return renderDb.getCollection(stackId.getTileCollectionName());
    }

    private DBCollection getTransformCollection(final StackId stackId) {
        return renderDb.getCollection(stackId.getTransformCollectionName());
    }

    private void validateRequiredParameter(final String context,
                                           final Object value)
            throws IllegalArgumentException {

        if (value == null) {
            throw new IllegalArgumentException(context + " value must be specified");
        }
    }

    private void ensureCoreTransformIndex(final DBCollection transformCollection) {
        ensureIndex(transformCollection,
                    new BasicDBObject("id", 1),
                    new BasicDBObject("unique", true).append("background", true));
        LOG.debug("ensureCoreTransformIndex: exit");
    }

    private void ensureCoreTileIndex(final DBCollection tileCollection) {
        ensureIndex(tileCollection,
                    new BasicDBObject("tileId", 1),
                    new BasicDBObject("unique", true).append("background", true));
        LOG.debug("ensureCoreTileIndex: exit");
    }

    private void ensureSupplementaryTileIndexes(final DBCollection tileCollection) {

        ensureIndex(tileCollection, new BasicDBObject("z", 1), BACKGROUND_OPTION);
        ensureIndex(tileCollection, new BasicDBObject("z", 1).append("minX", 1), BACKGROUND_OPTION);
        ensureIndex(tileCollection, new BasicDBObject("z", 1).append("minY", 1), BACKGROUND_OPTION);
        ensureIndex(tileCollection, new BasicDBObject("z", 1).append("maxX", 1), BACKGROUND_OPTION);
        ensureIndex(tileCollection, new BasicDBObject("z", 1).append("maxY", 1), BACKGROUND_OPTION);

        // compound index used for most box intersection queries
        // - z, minY, minX order used to match layout file sorting needs
        // - appended tileId so that getTileBounds query can be index only (must not sort)
        ensureIndex(tileCollection,
                    new BasicDBObject("z", 1).append(
                            "minY", 1).append("minX", 1).append("maxY", 1).append("maxX", 1).append("tileId", 1),
                    BACKGROUND_OPTION);

        // compound index used for group queries
        ensureIndex(tileCollection,
                    new BasicDBObject("z", 1).append(
                            "groupId", 1).append("minY", 1).append("minX", 1).append("maxY", 1).append("maxX", 1),
                    BACKGROUND_OPTION);

        LOG.debug("ensureSupplementaryTileIndexes: exit");
    }

    private void ensureIndex(final DBCollection collection,
                             final DBObject keys,
                             final DBObject options) {
        LOG.debug("ensureIndex: entry, collection={}, keys={}, options={}", collection.getName(), keys, options);
        collection.createIndex(keys, options);
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

    private static final Logger LOG = LoggerFactory.getLogger(RenderDao.class);

    private static final BasicDBObject BACKGROUND_OPTION = new BasicDBObject("background", true);
}

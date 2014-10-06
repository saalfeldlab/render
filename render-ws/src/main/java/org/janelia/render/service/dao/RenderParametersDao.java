package org.janelia.render.service.dao;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.mongodb.MongoCredential;
import com.mongodb.QueryOperators;
import com.mongodb.ServerAddress;
import com.mongodb.WriteResult;
import com.mongodb.util.JSON;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.ListTransformSpec;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.render.service.ObjectNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Data access object for render parameters.
 *
 * @author Eric Trautman
 */
public class RenderParametersDao {

    public static final String TILE_COLLECTION_NAME = "tile";
    public static final String TRANSFORM_COLLECTION_NAME = "transform";

    public static String getDatabaseName(String owner,
                                         String projectId,
                                         String stackId) {
        return owner + "-" + projectId + "-" + stackId;
    }

    private MongoClient client;

    public RenderParametersDao(DbConfig dbConfig)
            throws UnknownHostException {
        final ServerAddress serverAddress = new ServerAddress(dbConfig.getHost(), dbConfig.getPort());
        final MongoCredential credential = MongoCredential.createMongoCRCredential(dbConfig.getUserName(),
                                                                                   dbConfig.getAuthenticationDatabase(),
                                                                                   dbConfig.getPassword());
        client = new MongoClient(serverAddress, Arrays.asList(credential));
    }

    public RenderParametersDao(MongoClient client) {
        this.client = client;
    }

    /**
     * @return a render parameters object for the specified stack.
     *
     * @throws IllegalArgumentException
     *   if any required parameters are missing or the stack cannot be found.
     */
    public RenderParameters getParameters(String owner,
                                          String projectId,
                                          String stackId,
                                          Double x,
                                          Double y,
                                          Double z,
                                          Integer width,
                                          Integer height,
                                          Integer mipmapLevel)
            throws IllegalArgumentException {

        validateRequiredParameter("x", x);
        validateRequiredParameter("y", y);
        validateRequiredParameter("z", z);
        validateRequiredParameter("width", width);
        validateRequiredParameter("height", height);
        validateRequiredParameter("mipmapLevel", mipmapLevel);

        final DB db = getDatabase(owner, projectId, stackId);

        final DBCollection tileCollection = db.getCollection(TILE_COLLECTION_NAME);

        final double lowerRightX = x + width;
        final double lowerRightY = y + height;

        // intersection logic stolen from java.awt.Rectangle#intersects (without overflow checks)
        //   rx => minX, ry => minY, rw => maxX,        rh => maxY
        //   tx => x,    ty => y,    tw => lowerRightX, th => lowerRightY
        final DBObject tileQuery = new BasicDBObject("z", z).append(
                                                     "minX", lte(lowerRightX)).append(
                                                     "minY", lte(lowerRightY)).append(
                                                     "maxX", gte(x)).append(
                                                     "maxY", gte(y));

        RenderParameters renderParameters = new RenderParameters(null, x, y, width, height, mipmapLevel);

        final DBCursor cursor = tileCollection.find(tileQuery);
        try {
            DBObject document;
            TileSpec tileSpec;
            while (cursor.hasNext()) {
                document = cursor.next();
                tileSpec = JsonUtils.GSON.fromJson(document.toString(), TileSpec.class);
                renderParameters.addTileSpec(tileSpec);
            }
        } finally {
            cursor.close();
        }

        LOG.debug("getParameters: found {} tile spec(s) for {}.{}.find({})",
                  renderParameters.numberOfTileSpecs(), db.getName(), tileCollection.getName(), tileQuery);

        resolveTransformReferencesForTiles(db, renderParameters.getTileSpecs());

        // TODO: is returning black image okay or do we want to throw an exception?
//        if (! renderParameters.hasTileSpecs()) {
//            throw new IllegalArgumentException("no tile specifications found");
//        }

        return renderParameters;
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
    public TileSpec getTileSpec(String owner,
                                String projectId,
                                String stackId,
                                String tileId)
            throws IllegalArgumentException,
                   ObjectNotFoundException {

        validateRequiredParameter("tileId", tileId);

        final DB db = getDatabase(owner, projectId, stackId);
        final DBCollection tileCollection = db.getCollection(TILE_COLLECTION_NAME);

        final BasicDBObject query = new BasicDBObject();
        query.put("tileId", tileId);

        LOG.debug("getTileSpec: {}.{}.find({})",
                  tileCollection.getDB().getName(), tileCollection.getName(), query);

        final DBObject document = tileCollection.findOne(query);

        if (document == null) {
            throw new ObjectNotFoundException("tile spec with id '" + tileId + "' does not exist in the " +
                                              db.getName() + " " + TILE_COLLECTION_NAME + " collection");
        }

        return JsonUtils.GSON.fromJson(document.toString(), TileSpec.class);
    }

    /**
     * Saves the specified tile spec to the database.
     *
     * @param  owner      data owner.
     * @param  projectId  project identifier.
     * @param  stackId    stack identifier.
     * @param  tileSpec   specification to be saved.
     *
     * @return the specification updated with any attributes that were modified by the save.
     *
     * @throws IllegalArgumentException
     *   if any required parameters or transform spec references are missing.
     */
    public TileSpec saveTileSpec(String owner,
                                 String projectId,
                                 String stackId,
                                 TileSpec tileSpec)
            throws IllegalArgumentException {

        validateRequiredParameter("tileSpec", tileSpec);
        validateRequiredParameter("tileSpec.tileId", tileSpec.getTileId());

        final DB db = getDatabase(owner, projectId, stackId);
        final DBCollection tileCollection = db.getCollection(TILE_COLLECTION_NAME);

        final String context = "tile spec with id '" + tileSpec.getTileId();
        validateTransformReferences(context, tileSpec.getTransforms(), db);

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

        LOG.debug("saveTileSpec: {}.{}.{},({}), upsertedId is {}",
                  tileCollection.getDB().getName(), TILE_COLLECTION_NAME, action, query, result.getUpsertedId());

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
    public TransformSpec getTransformSpec(String owner,
                                          String projectId,
                                          String stackId,
                                          String transformId)
            throws IllegalArgumentException,
                   ObjectNotFoundException {

        validateRequiredParameter("transformId", transformId);

        final DB db = getDatabase(owner, projectId, stackId);
        final DBCollection transformCollection = db.getCollection(TRANSFORM_COLLECTION_NAME);

        final BasicDBObject query = new BasicDBObject();
        query.put("id", transformId);

        LOG.debug("getTransformSpec: {}.{}.find({})",
                  transformCollection.getDB().getName(), transformCollection.getName(), query);

        final DBObject document = transformCollection.findOne(query);

        if (document == null) {
            throw new ObjectNotFoundException("transform spec with id '" + transformId + "' does not exist in the " +
                                              db.getName() + " " + TRANSFORM_COLLECTION_NAME + " collection");
        }

        return JsonUtils.GSON.fromJson(document.toString(), TransformSpec.class);
    }

    /**
     * Saves the specified transform spec to the database.
     *
     * @param  owner          data owner.
     * @param  projectId      project identifier.
     * @param  stackId        stack identifier.
     * @param  transformSpec  specification to be saved.
     *
     * @return the specification updated with any attributes that were modified by the save.
     *
     * @throws IllegalArgumentException
     *   if any required parameters or transform spec references are missing.
     */
    public TransformSpec saveTransformSpec(String owner,
                                           String projectId,
                                           String stackId,
                                           TransformSpec transformSpec)
            throws IllegalArgumentException {

        validateRequiredParameter("transformSpec", transformSpec);
        validateRequiredParameter("transformSpec.id", transformSpec.getId());

        final DB db = getDatabase(owner, projectId, stackId);
        final DBCollection transformCollection = db.getCollection(TRANSFORM_COLLECTION_NAME);

        final String context = "transform spec with id '" + transformSpec.getId() + "'";
        validateTransformReferences(context, transformSpec, db);

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

        LOG.debug("saveTransformSpec: {}.{}.{},({}), upsertedId is {}",
                  transformCollection.getDB().getName(), TRANSFORM_COLLECTION_NAME, action, query, result.getUpsertedId());

        return transformSpec;
    }

    private DB getDatabase(String owner,
                           String projectId,
                           String stackId) {
        validateIdName("owner", owner);
        validateIdName("projectId", projectId);
        validateIdName("stackId", stackId);

        return client.getDB(getDatabaseName(owner, projectId, stackId));
    }

    private List<TransformSpec> getTransformSpecs(DBCollection transformCollection,
                                                  Set<String> specIds) {
        final int specCount = specIds.size();
        final List<TransformSpec> transformSpecList = new ArrayList<TransformSpec>(specCount);
        if (specCount > 0) {

            BasicDBObject transformQuery = new BasicDBObject();
            transformQuery.put("id", new BasicDBObject(QueryOperators.IN, specIds));

            LOG.debug("getTransformSpecs: {}.{}.find({})",
                      transformCollection.getDB().getName(), transformCollection.getName(), transformQuery);

            final DBCursor cursor = transformCollection.find(transformQuery);
            try {
                DBObject document;
                TransformSpec transformSpec;
                while (cursor.hasNext()) {
                    document = cursor.next();
                    transformSpec = JsonUtils.GSON.fromJson(document.toString(), TransformSpec.class);
                    transformSpecList.add(transformSpec);
                }
            } finally {
                cursor.close();
            }

        }

        return transformSpecList;
    }

    private void getDataForTransformSpecReferences(DBCollection transformCollection,
                                                   Set<String> unresolvedSpecIds,
                                                   Map<String, TransformSpec> resolvedIdToSpecMap,
                                                   int callCount) {

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

            final Set<String> newlyUnresolvedSpecIds = new HashSet<String>();

            for (TransformSpec spec : transformSpecList) {
                resolvedIdToSpecMap.put(spec.getId(), spec);
                for (String id : spec.getUnresolvedIds()) {
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

    private void resolveTransformReferencesForTiles(DB db,
                                                    List<TileSpec> tileSpecs)
            throws IllegalStateException {

        final Set<String> unresolvedIds = new HashSet<String>();
        ListTransformSpec transforms;
        for (TileSpec tileSpec : tileSpecs) {
            transforms = tileSpec.getTransforms();
            if (transforms != null) {
                transforms.addUnresolvedIds(unresolvedIds);
            }
        }

        final int unresolvedCount = unresolvedIds.size();
        if (unresolvedCount > 0) {

            final DBCollection transformCollection = db.getCollection(TRANSFORM_COLLECTION_NAME);
            final Map<String, TransformSpec> resolvedIdToSpecMap = new HashMap<String, TransformSpec>();

            getDataForTransformSpecReferences(transformCollection, unresolvedIds, resolvedIdToSpecMap, 1);

            // resolve any references within the retrieved transform specs
            for (TransformSpec transformSpec : resolvedIdToSpecMap.values()) {
                transformSpec.resolveReferences(resolvedIdToSpecMap);
            }

            // apply fully resolved transform specs to tiles
            for (TileSpec tileSpec : tileSpecs) {
                transforms = tileSpec.getTransforms();
                transforms.resolveReferences(resolvedIdToSpecMap);
                if (! transforms.isFullyResolved()) {
                    throw new IllegalStateException("tile spec " + tileSpec.getTileId() +
                                                    " is not fully resolved after applying the following transform specs: " +
                                                    resolvedIdToSpecMap.keySet());
                }
            }

        }
    }

    private DBObject lte(double value) {
        return new BasicDBObject(QueryOperators.LTE, value);
    }

    private DBObject gte(double value) {
        return new BasicDBObject(QueryOperators.GTE, value);
    }

    private void validateIdName(String context,
                                String idName)
            throws IllegalArgumentException {

        validateRequiredParameter(context, idName);

        final Matcher m = VALID_ID_NAME.matcher(idName);
        if (! m.matches()) {
            throw new IllegalArgumentException("invalid " + context + " name '" + idName + "' specified");
        }
    }

    private void validateRequiredParameter(String context,
                                           Object value)
            throws IllegalArgumentException {

        if (value == null) {
            throw new IllegalArgumentException(context + " value must be specified");
        }
    }

    private void validateTransformReferences(String context,
                                             TransformSpec transformSpec,
                                             DB db) {

        final Set<String> unresolvedTransformSpecIds = transformSpec.getUnresolvedIds();

        if (unresolvedTransformSpecIds.size() > 0) {
            final DBCollection transformCollection = db.getCollection(TRANSFORM_COLLECTION_NAME);
            final List<TransformSpec> transformSpecList = getTransformSpecs(transformCollection,
                                                                            unresolvedTransformSpecIds);
            if (transformSpecList.size() != unresolvedTransformSpecIds.size()) {
                final Set<String> existingIds = new HashSet<String>(transformSpecList.size());
                for (TransformSpec existingTransformSpec : transformSpecList) {
                    existingIds.add(existingTransformSpec.getId());
                }
                final Set<String> missingIds = new TreeSet<String>();
                for (String id : unresolvedTransformSpecIds) {
                    if (! existingIds.contains(id)) {
                        missingIds.add(id);
                    }
                }
                throw new IllegalArgumentException(context + " references transform id(s) " + missingIds +
                                                   " which do not exist in the " + db.getName() + "." +
                                                   TRANSFORM_COLLECTION_NAME + " collection");
            }
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderParametersDao.class);
    private static final Pattern VALID_ID_NAME = Pattern.compile("[A-Za-z0-9\\-]++");
}

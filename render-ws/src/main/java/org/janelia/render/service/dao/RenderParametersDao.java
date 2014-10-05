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
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.ListTransformSpec;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
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

    public static String getDatabaseName(String projectId,
                                         String stackId) {
        return projectId + "-" + stackId;
    }

    private MongoClient client;

    public RenderParametersDao(DbConfig dbConfig)
            throws UnknownHostException {
        final ServerAddress serverAddress = new ServerAddress(dbConfig.getHost(), dbConfig.getPort());
        final MongoCredential credential = MongoCredential.createMongoCRCredential(dbConfig.getUserName(),
                                                                                   dbConfig.getUserNameSource(),
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
    public RenderParameters getParameters(String projectId,
                                          String stackId,
                                          Double x,
                                          Double y,
                                          Double z,
                                          Integer width,
                                          Integer height,
                                          Integer zoomLevel)
            throws IllegalArgumentException {

        validateRequiredParameter("x", x);
        validateRequiredParameter("y", y);
        validateRequiredParameter("z", z);
        validateRequiredParameter("width", width);
        validateRequiredParameter("height", height);
        validateRequiredParameter("zoomLevel", zoomLevel);

        final DB db = getDatabase(projectId, stackId);

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

        RenderParameters renderParameters = new RenderParameters(null, x, y, width, height, zoomLevel);

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

    private DB getDatabase(String projectId,
                           String stackId) {
        validateIdName("projectId", projectId);
        validateIdName("stackId", stackId);

        return client.getDB(getDatabaseName(projectId, stackId));
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

    private static final Logger LOG = LoggerFactory.getLogger(RenderParametersDao.class);
    private static final Pattern VALID_ID_NAME = Pattern.compile("[A-Za-z0-9\\-]++");
}

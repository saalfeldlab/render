package org.janelia.render.service;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.mongodb.MongoCredential;
import com.mongodb.QueryOperators;
import com.mongodb.ServerAddress;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.TileSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Data access object for render parameters.
 *
 * @author Eric Trautman
 */
public class RenderParametersDao {

    private DB db;

    public RenderParametersDao()
            throws UnknownHostException {
        // TODO: load connection parameters from a file (currently need to insert actual password below)
        this("tile-mongodb.int.janelia.org",
             "tileAdmin",
             "tile",
             "<password-here>");
    }

    /**
     * @param  host  database host.
     */
    public RenderParametersDao(String host,
                               String userName,
                               String databaseName,
                               String password)
            throws UnknownHostException {
        final ServerAddress serverAddress = new ServerAddress(host);
        final MongoCredential credential = MongoCredential.createMongoCRCredential(userName,
                                                                                   databaseName,
                                                                                   password.toCharArray());
        MongoClient client = new MongoClient(serverAddress, Arrays.asList(credential));
        this.db = client.getDB(databaseName);
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

        validateIdName("projectId", projectId);
        validateIdName("stackId", stackId);
        validateRequiredParameter("x", x);
        validateRequiredParameter("y", y);
        validateRequiredParameter("z", z);
        validateRequiredParameter("width", width);
        validateRequiredParameter("height", height);
        validateRequiredParameter("zoomLevel", zoomLevel);

        // TODO: integrate project into collection name

        // TODO: this will create the collection if it doesn't exist, need efficient way to detect existence
        final DBCollection dbCollection = db.getCollection(stackId);

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

        final DBCursor cursor = dbCollection.find(tileQuery);
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

        // TODO: is returning black image okay or do we want to throw an exception?
//        if (! renderParameters.hasTileSpecs()) {
//            throw new IllegalArgumentException("no tile specifications found");
//        }

        LOG.debug("found {} tile spec(s) matching {}", renderParameters.numberOfTileSpecs(), tileQuery);

        return renderParameters;
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

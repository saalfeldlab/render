package org.janelia.render.client;

import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileCoordinates;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackVersion;
import org.janelia.render.client.response.JsonResponseHandler;
import org.janelia.render.client.response.ResourceCreatedResponseHandler;
import org.janelia.render.client.response.TextResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.janelia.alignment.spec.stack.StackMetaData.StackState;

/**
 * HTTP client "wrapper" for retrieving and storing render data via the render web service.
 *
 * @author Eric Trautman
 */
@SuppressWarnings("UnusedDeclaration")
public class RenderDataClient {

    private final String baseDataUrl;
    private final String owner;
    private final String project;

    private final CloseableHttpClient httpClient;

    /**
     * Creates a new client for the specified owner and project.
     *
     * @param  baseDataUrl  the base URL string for all requests (e.g. 'http://tem-services:8080/render-ws/v1')
     * @param  owner        the owner name for all requests.
     * @param  project      the project name for all requests.
     */
    public RenderDataClient(final String baseDataUrl,
                            final String owner,
                            final String project) {
        this.baseDataUrl = baseDataUrl;
        this.owner = owner;
        this.project = project;
        this.httpClient = HttpClients.createDefault();
    }

    @Override
    public String toString() {
        return "{baseDataUrl='" + baseDataUrl + '\'' +
               ", owner='" + owner + '\'' +
               ", project='" + project + '\'' +
               '}';
    }

    /**
     * @return a "likely" unique identifier from the server.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public String getLikelyUniqueId()
            throws IOException {

        final URI uri = getUri(getOwnerUrlString() + "/likelyUniqueId");
        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final JsonResponseHandler<String> responseHandler =
                new JsonResponseHandler<>(requestContext, String.class);

        LOG.info("getLikelyUniqueId: submitting {}", requestContext);

        return httpClient.execute(httpGet, responseHandler);
    }

    /**
     * @param  stack  name of stack.
     *
     * @return meta data for the specified stack.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public StackMetaData getStackMetaData(final String stack)
            throws IOException {

        final URI uri = getStackUri(stack);
        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final JsonResponseHandler<StackMetaData> responseHandler =
                new JsonResponseHandler<>(requestContext, StackMetaData.class);

        LOG.info("getStackMetaData: submitting {}", requestContext);

        return httpClient.execute(httpGet, responseHandler);
    }

    /**
     * @param  stack  name of stack.
     *
     * @return z values for the specified stack.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public List<Double> getStackZValues(final String stack)
            throws IOException {

        final URI uri = getUri(getStackUrlString(stack) + "/zValues");
        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final Type typeOfT = new TypeToken<List<Double>>(){}.getType();
        final JsonResponseHandler<List<Double>> responseHandler =
                new JsonResponseHandler<>(requestContext, typeOfT);

        LOG.info("getStackZValues: submitting {}", requestContext);

        return httpClient.execute(httpGet, responseHandler);
    }

    /**
     * Saves the specified version data.
     *
     * @param  stack         name of stack.
     * @param  stackVersion  version data to save.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public void saveStackVersion(final String stack,
                                 final StackVersion stackVersion)
            throws IOException {

        final String json = JsonUtils.GSON.toJson(stackVersion);
        final StringEntity stringEntity = new StringEntity(json, ContentType.APPLICATION_JSON);
        final URI uri = getStackUri(stack);
        final String requestContext = "POST " + uri;
        final ResourceCreatedResponseHandler responseHandler = new ResourceCreatedResponseHandler(requestContext);

        final HttpPost httpPost = new HttpPost(uri);
        httpPost.setEntity(stringEntity);

        LOG.info("saveStackVersion: submitting {}", requestContext);

        httpClient.execute(httpPost, responseHandler);
    }

    /**
     * Clones the specified stack.
     *
     * @param  fromStack       source stack to clone.
     * @param  toStack         new stack to hold cloned data.
     * @param  toStackVersion  version data for the new stack.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public void cloneStackVersion(final String fromStack,
                                  final String toStack,
                                  final StackVersion toStackVersion)
            throws IOException {

        final String json = JsonUtils.GSON.toJson(toStackVersion);
        final StringEntity stringEntity = new StringEntity(json, ContentType.APPLICATION_JSON);
        final URI uri = getUri(getStackUrlString(fromStack) + "/cloneTo/" + toStack);
        final String requestContext = "PUT " + uri;
        final ResourceCreatedResponseHandler responseHandler = new ResourceCreatedResponseHandler(requestContext);

        final HttpPut httpPut = new HttpPut(uri);
        httpPut.setEntity(stringEntity);

        LOG.info("cloneStackVersion: submitting {}", requestContext);

        httpClient.execute(httpPut, responseHandler);
    }

    /**
     * Changes the state of the specified stack.
     *
     * @param  stack       stack to change.
     * @param  stackState  new state for stack.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public void setStackState(final String stack,
                              final StackState stackState)
            throws IOException {

        final URI uri = getUri(getStackUrlString(stack) + "/state/" + stackState);
        final String requestContext = "PUT " + uri;
        final ResourceCreatedResponseHandler responseHandler = new ResourceCreatedResponseHandler(requestContext);

        final HttpPut httpPut = new HttpPut(uri);

        LOG.info("setStackState: submitting {}", requestContext);

        httpClient.execute(httpPut, responseHandler);
    }

    /**
     * Deletes the specified stack or a section of the specified stack.
     *
     * @param  stack  stack to delete.
     * @param  z      z value for layer to delete (or null to delete all layers).
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public void deleteStack(final String stack,
                            final Double z)
            throws IOException {

        final URI uri;
        if (z == null) {
            uri = getStackUri(stack);
        } else {
            uri = getUri(getZUrlString(stack, z));
        }
        final String requestContext = "DELETE " + uri;
        final TextResponseHandler responseHandler = new TextResponseHandler(requestContext);

        final HttpDelete httpDelete = new HttpDelete(uri);

        LOG.info("deleteStack: submitting {}", requestContext);

        httpClient.execute(httpDelete, responseHandler);
    }

    /**
     * @param  stack   stack containing tile.
     * @param  tileId  identifies tile.
     *
     * @return the tile spec for the specified tile.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public TileSpec getTile(final String stack,
                            final String tileId)
            throws IOException {

        final URI uri = getUri(getStackUrlString(stack) + "/tile/" + tileId);
        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final JsonResponseHandler<TileSpec> responseHandler =
                new JsonResponseHandler<>(requestContext, TileSpec.class);

        LOG.info("getResolvedTiles: submitting {}", requestContext);

        return httpClient.execute(httpGet, responseHandler);
    }

    /**
     * @param  stack  name of stack.
     * @param  z      z value for layer.
     *
     * @return bounds for the specified layer.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public Bounds getLayerBounds(final String stack,
                                 final Double z)
            throws IOException {

        final URI uri = getUri(getZUrlString(stack, z) + "/bounds");
        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final JsonResponseHandler<Bounds> responseHandler =
                new JsonResponseHandler<>(requestContext, Bounds.class);

        LOG.info("getLayerBounds: submitting {}", requestContext);

        return httpClient.execute(httpGet, responseHandler);
    }

    /**
     * @param  stack  name of stack.
     * @param  z      z value for layer.
     *
     * @return list of tile bounds for the specified layer.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public List<TileBounds> getTileBounds(final String stack,
                                          final Double z)
            throws IOException {

        final URI uri = getUri(getZUrlString(stack, z) + "/tileBounds");
        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final Type typeOfT = new TypeToken<List<TileBounds>>(){}.getType();
        final JsonResponseHandler<List<TileBounds>> responseHandler =
                new JsonResponseHandler<>(requestContext, typeOfT);

        LOG.info("getTileBounds: submitting {}", requestContext);

        return httpClient.execute(httpGet, responseHandler);
    }

    /**
     * @param  stack  name of stack.
     * @param  z      z value for layer.
     *
     * @return the set of resolved tiles and transforms for the specified layer.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public ResolvedTileSpecCollection getResolvedTiles(final String stack,
                                                       final Double z)
            throws IOException {

        final URI uri = getResolvedTilesUri(stack, z);
        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final JsonResponseHandler<ResolvedTileSpecCollection> responseHandler =
                new JsonResponseHandler<>(requestContext, ResolvedTileSpecCollection.class);

        LOG.info("getResolvedTiles: submitting {}", requestContext);

        return httpClient.execute(httpGet, responseHandler);
    }

    /**
     * Saves the specified collection.
     *
     * @param  resolvedTiles  collection of tile and transform specs to save.
     * @param  stack          name of stack.
     * @param  z              optional z value for all tiles; specify null if tiles have differing z values.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public void saveResolvedTiles(final ResolvedTileSpecCollection resolvedTiles,
                                  final String stack,
                                  final Double z)
            throws IOException {

        final String json = JsonUtils.GSON.toJson(resolvedTiles);
        final StringEntity stringEntity = new StringEntity(json, ContentType.APPLICATION_JSON);
        final URI uri = getResolvedTilesUri(stack, z);
        final String requestContext = "PUT " + uri;
        final ResourceCreatedResponseHandler responseHandler = new ResourceCreatedResponseHandler(requestContext);

        final HttpPut httpPut = new HttpPut(uri);
        httpPut.setEntity(stringEntity);

        LOG.info("saveResolvedTiles: submitting {}", requestContext);

        httpClient.execute(httpPut, responseHandler);
    }

    /**
     * Saves the specified matches.
     *
     * @param  canvasMatches  matches to save.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public void saveMatches(final List<CanvasMatches> canvasMatches)
            throws IOException {

        final String json = JsonUtils.GSON.toJson(canvasMatches);
        final StringEntity stringEntity = new StringEntity(json, ContentType.APPLICATION_JSON);
        final URI uri = getUri(getOwnerUrlString() + "/matchCollection/" + project + "/matches");
        final String requestContext = "PUT " + uri;
        final ResourceCreatedResponseHandler responseHandler = new ResourceCreatedResponseHandler(requestContext);

        final HttpPut httpPut = new HttpPut(uri);
        httpPut.setEntity(stringEntity);

        LOG.info("saveMatches: submitting {} for {} matches", requestContext, canvasMatches.size());

        httpClient.execute(httpPut, responseHandler);
    }

    /**
     * Sends the specified world coordinates to the server to be mapped to tiles.
     * Because tiles overlap, each coordinate can potentially be mapped to multiple tiles.
     * The result is a list of coordinate lists for all mapped tiles.
     *
     * For example,
     * <pre>
     *     Given:                 Result could be:
     *     [                      [
     *       { world: [1,2] },        [ { tileId: A, world: [1,2] }, { tileId: B, world: [1,2] } ],
     *       { world: [3,4] }         [ { tileId: C, world: [3,4] } ]
     *     ]                      ]
     * </pre>
     *
     * @param  worldCoordinates  world coordinates to be mapped to tiles.
     * @param  stack             name of stack.
     * @param  z                 z value for layer.
     *
     * @return list of coordinate lists.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public List<List<TileCoordinates>> getTileIdsForCoordinates(final List<TileCoordinates> worldCoordinates,
                                                                final String stack,
                                                                final Double z)
            throws IOException {

        final String worldCoordinatesJson = JsonUtils.GSON.toJson(worldCoordinates);
        final StringEntity stringEntity = new StringEntity(worldCoordinatesJson, ContentType.APPLICATION_JSON);
        final URI uri = getUri(getZUrlString(stack, z) + "/tileIdsForCoordinates");
        final String requestContext = "PUT " + uri;

        final HttpPut httpPut = new HttpPut(uri);
        httpPut.setEntity(stringEntity);

        final Type typeOfT = new TypeToken<List<List<TileCoordinates>>>(){}.getType();
        final JsonResponseHandler<List<List<TileCoordinates>>> responseHandler =
                new JsonResponseHandler<>(requestContext, typeOfT);

        LOG.info("getTileIdsForCoordinates: submitting {}", requestContext);

        return httpClient.execute(httpPut, responseHandler);
    }

    /**
     * @param  stack   name of stack.
     * @param  x       x value for box.
     * @param  y       y value for box.
     * @param  z       z value for box.
     * @param  width   width of box.
     * @param  height  height of box.
     * @param  scale   scale of target image.
     *
     * @return a render parameters URL string composed from the specified values.
     */
    public String getRenderParametersUrlString(final String stack,
                                               final double x,
                                               final double y,
                                               final double z,
                                               final int width,
                                               final int height,
                                               final double scale) {
        return getZUrlString(stack, z) + "/box/" +
               x + ',' + y + ',' + width + ',' + height + ',' + scale +
               "/render-parameters";
    }

    private String getOwnerUrlString() {
        return baseDataUrl + "/owner/" + owner;
    }

    private String getStackUrlString(final String stack) {
        return getOwnerUrlString() + "/project/" + project + "/stack/" + stack;
    }

    private String getZUrlString(final String stack,
                                 final Double z) {
        return getStackUrlString(stack) + "/z/" + z;
    }

    private URI getStackUri(final String stack)
            throws IOException {
        return getUri(getStackUrlString(stack));
    }

    private URI getResolvedTilesUri(final String stack,
                                    final Double z)
            throws IOException {
        final String baseUrlString;
        if (z == null) {
            baseUrlString = getStackUrlString(stack);
        } else {
            baseUrlString = getZUrlString(stack, z);
        }
        return getUri(baseUrlString + "/resolvedTiles");
    }

    private URI getUri(final String forString)
            throws IOException {
        final URI uri;
        try {
            uri = new URI(forString);
        } catch (final URISyntaxException e) {
            throw new IOException("failed to create URI for '" + forString + "'", e);
        }
        return uri;
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderDataClient.class);
}

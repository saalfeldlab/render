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
public class RenderDataClient {

    private final String baseDataUrl;
    private final String owner;
    private final String project;

    private CloseableHttpClient httpClient;

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

    public StackMetaData getStackMetaData(String stack)
            throws IllegalArgumentException, IOException {

        final URI uri = getStackUri(stack);
        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final JsonResponseHandler<StackMetaData> responseHandler =
                new JsonResponseHandler<>(requestContext, StackMetaData.class);

        LOG.info("getStackMetaData: submitting {}", requestContext);

        return httpClient.execute(httpGet, responseHandler);
    }

    public void saveStackVersion(String stack,
                                 StackVersion stackVersion)
            throws IllegalArgumentException, IOException {

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

    public void cloneStackVersion(String fromStack,
                                  String toStack,
                                  StackVersion toStackVersion)
            throws IllegalArgumentException, IOException {

        final String json = JsonUtils.GSON.toJson(toStackVersion);
        final StringEntity stringEntity = new StringEntity(json, ContentType.APPLICATION_JSON);
        final URI uri = getStackCloneUri(fromStack, toStack);
        final String requestContext = "PUT " + uri;
        final ResourceCreatedResponseHandler responseHandler = new ResourceCreatedResponseHandler(requestContext);

        final HttpPut httpPut = new HttpPut(uri);
        httpPut.setEntity(stringEntity);

        LOG.info("cloneStackVersion: submitting {}", requestContext);

        httpClient.execute(httpPut, responseHandler);
    }

    public void setStackState(String stack,
                              StackState stackState)
            throws IllegalArgumentException, IOException {

        final URI uri = getStackStateUri(stack, stackState);
        final String requestContext = "PUT " + uri;
        final ResourceCreatedResponseHandler responseHandler = new ResourceCreatedResponseHandler(requestContext);

        final HttpPut httpPut = new HttpPut(uri);

        LOG.info("setStackState: submitting {}", requestContext);

        httpClient.execute(httpPut, responseHandler);
    }

    public void deleteStack(String stack)
            throws IllegalArgumentException, IOException {

        final URI uri = getStackUri(stack);
        final String requestContext = "DELETE " + uri;
        final TextResponseHandler responseHandler = new TextResponseHandler(requestContext);

        final HttpDelete httpDelete = new HttpDelete(uri);

        LOG.info("deleteStack: submitting {}", requestContext);

        httpClient.execute(httpDelete, responseHandler);
    }

    public TileSpec getTile(String stack,
                            String tileId)
            throws IllegalArgumentException, IOException {

        final URI uri = getTileUri(stack, tileId);
        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final JsonResponseHandler<TileSpec> responseHandler =
                new JsonResponseHandler<>(requestContext, TileSpec.class);

        LOG.info("getResolvedTiles: submitting {}", requestContext);

        return httpClient.execute(httpGet, responseHandler);
    }

    public Bounds getLayerBounds(String stack,
                                 Double z)
            throws IllegalArgumentException, IOException {

        final URI uri = getLayerBoundsUri(stack, z);
        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final JsonResponseHandler<Bounds> responseHandler =
                new JsonResponseHandler<>(requestContext, Bounds.class);

        LOG.info("getLayerBounds: submitting {}", requestContext);

        return httpClient.execute(httpGet, responseHandler);
    }

    public List<TileBounds> getTileBounds(String stack,
                                          Double z)
            throws IllegalArgumentException, IOException {

        final URI uri = getTileBoundsUri(stack, z);
        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final Type typeOfT = new TypeToken<List<TileBounds>>(){}.getType();
        final JsonResponseHandler<List<TileBounds>> responseHandler =
                new JsonResponseHandler<>(requestContext, typeOfT);

        LOG.info("getTileBounds: submitting {}", requestContext);

        return httpClient.execute(httpGet, responseHandler);
    }

    public ResolvedTileSpecCollection getResolvedTiles(String stack,
                                                       Double z)
            throws IllegalArgumentException, IOException {

        final URI uri = getResolvedTilesUri(stack, z);
        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final JsonResponseHandler<ResolvedTileSpecCollection> responseHandler =
                new JsonResponseHandler<>(requestContext, ResolvedTileSpecCollection.class);

        LOG.info("getResolvedTiles: submitting {}", requestContext);

        return httpClient.execute(httpGet, responseHandler);
    }

    public void saveResolvedTiles(ResolvedTileSpecCollection resolvedTiles,
                                  String stack,
                                  Double z)
            throws IllegalArgumentException, IOException {

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

    public void saveMatches(List<CanvasMatches> canvasMatches)
            throws IllegalArgumentException, IOException {

        final String json = JsonUtils.GSON.toJson(canvasMatches);
        final StringEntity stringEntity = new StringEntity(json, ContentType.APPLICATION_JSON);
        final URI uri = getMatchesUri();
        final String requestContext = "PUT " + uri;
        final ResourceCreatedResponseHandler responseHandler = new ResourceCreatedResponseHandler(requestContext);

        final HttpPut httpPut = new HttpPut(uri);
        httpPut.setEntity(stringEntity);

        LOG.info("saveMatches: submitting {} for {} matches", requestContext, canvasMatches.size());

        httpClient.execute(httpPut, responseHandler);
    }

    public List<List<TileCoordinates>> getTileIdsForCoordinates(List<TileCoordinates> worldCoordinates,
                                                                String stack,
                                                                Double z)
            throws IOException {

        final String worldCoordinatesJson = JsonUtils.GSON.toJson(worldCoordinates);
        final StringEntity stringEntity = new StringEntity(worldCoordinatesJson, ContentType.APPLICATION_JSON);
        final URI uri = getTileIdsForCoordinatesUri(stack, z);
        final String requestContext = "PUT " + uri;

        final HttpPut httpPut = new HttpPut(uri);
        httpPut.setEntity(stringEntity);

        final Type typeOfT = new TypeToken<List<List<TileCoordinates>>>(){}.getType();
        final JsonResponseHandler<List<List<TileCoordinates>>> responseHandler =
                new JsonResponseHandler<>(requestContext, typeOfT);

        LOG.info("getTileIdsForCoordinates: submitting {}", requestContext);

        return httpClient.execute(httpPut, responseHandler);
    }

    public String getStackUrlString(final String stack) {
        return baseDataUrl + "/owner/" + owner + "/project/" + project + "/stack/" + stack;
    }

    public String getZUrlString(final String stack,
                                final Double z) {
        return getStackUrlString(stack) + "/z/" + z;
    }

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

    private URI getStackUri(final String stack) {
        return getUri(getStackUrlString(stack));
    }

    private URI getStackCloneUri(final String fromStack,
                                 final String toStack) {
        return getUri(getStackUrlString(fromStack) + "/cloneTo/" + toStack);
    }

    private URI getStackStateUri(final String stack,
                                 final StackState stackState) {
        return getUri(getStackUrlString(stack) + "/state/" + stackState);
    }

    private URI getTileUri(final String stack,
                           final String tileId) {
        return getUri(getStackUrlString(stack) + "/tile/" + tileId);
    }

    private URI getResolvedTilesUri(String stack,
                                    Double z) {
        String baseUrlString;
        if (z == null) {
            baseUrlString = getStackUrlString(stack);
        } else {
            baseUrlString = getZUrlString(stack, z);
        }
        return getUri(baseUrlString + "/resolvedTiles");
    }

    private URI getLayerBoundsUri(String stack,
                                  Double z) {
        return getUri(getZUrlString(stack, z) + "/bounds");
    }

    private URI getTileBoundsUri(String stack,
                                 Double z) {
        return getUri(getZUrlString(stack, z) + "/tileBounds");
    }

    private URI getTileIdsForCoordinatesUri(String stack,
                                            Double z) {
        return getUri(getZUrlString(stack, z) + "/tileIdsForCoordinates");
    }

    public URI getMatchesUri() {
        return getUri(baseDataUrl + "/owner/" + owner + "/matchCollection/" + project + "/matches");
    }

    private URI getUri(String forString) throws IllegalArgumentException {
        final URI uri;
        try {
            uri = new URI(forString);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("failed to create URI for '" + forString + "'", e);
        }
        return uri;
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderDataClient.class);
}

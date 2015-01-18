package org.janelia.render.client;

import com.google.gson.reflect.TypeToken;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.response.JsonResponseHandler;
import org.janelia.render.client.response.ResourceCreatedResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

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

    public TileSpec getTile(String stack,
                            String tileId)
            throws URISyntaxException, IOException {

        final URI uri = getTileUri(stack, tileId);
        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final JsonResponseHandler<TileSpec> responseHandler = new JsonResponseHandler<TileSpec>(requestContext,
                                                                                                TileSpec.class);

        LOG.info("getResolvedTiles: submitting {}", requestContext);

        return httpClient.execute(httpGet, responseHandler);
    }

    public Bounds getLayerBounds(String stack,
                                 Double z)
            throws URISyntaxException, IOException {

        final URI uri = getLayerBoundsUri(stack, z);
        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final JsonResponseHandler<Bounds> responseHandler =
                new JsonResponseHandler<Bounds>(requestContext,
                                                Bounds.class);

        LOG.info("getLayerBounds: submitting {}", requestContext);

        return httpClient.execute(httpGet, responseHandler);
    }

    public List<TileBounds> getTileBounds(String stack,
                                          Double z)
            throws URISyntaxException, IOException {

        final URI uri = getTileBoundsUri(stack, z);
        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final Type typeOfT = new TypeToken<List<TileBounds>>(){}.getType();
        final JsonResponseHandler<List<TileBounds>> responseHandler =
                new JsonResponseHandler<List<TileBounds>>(requestContext,
                                                          typeOfT);

        LOG.info("getTileBounds: submitting {}", requestContext);

        return httpClient.execute(httpGet, responseHandler);
    }

    public ResolvedTileSpecCollection getResolvedTiles(String stack,
                                                       Double z)
            throws URISyntaxException, IOException {

        final URI uri = getResolvedTilesUri(stack, z);
        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final JsonResponseHandler<ResolvedTileSpecCollection> responseHandler =
                new JsonResponseHandler<ResolvedTileSpecCollection>(requestContext,
                                                                    ResolvedTileSpecCollection.class);

        LOG.info("getResolvedTiles: submitting {}", requestContext);

        return httpClient.execute(httpGet, responseHandler);
    }

    public void saveResolvedTiles(ResolvedTileSpecCollection resolvedTiles,
                                   String stack,
                                   Double z)
            throws URISyntaxException, IOException {
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

    public String getStackUrlString(String stack)
            throws URISyntaxException {
        return baseDataUrl + "/owner/" + owner + "/project/" + project + "/stack/" + stack;
    }

    public String getZUrlString(String stack,
                                 Double z)
            throws URISyntaxException {
        return getStackUrlString(stack) + "/z/" + z;
    }

    public String getRenderParametersUrlString(final String stack,
                                               final double x,
                                               final double y,
                                               final double z,
                                               final int width,
                                               final int height,
                                               final double scale)
            throws URISyntaxException {
        return getZUrlString(stack, z) + "/box/" +
               x + ',' + y + ',' + width + ',' + height + ',' + scale +
               "/render-parameters";
    }

    private URI getTileUri(String stack,
                           String tileId)
            throws URISyntaxException {
        return new URI(getStackUrlString(stack) + "/tile/" + tileId);
    }

    private URI getResolvedTilesUri(String stack,
                                    Double z)
            throws URISyntaxException {
        return new URI(getZUrlString(stack, z) + "/resolvedTiles");
    }

    private URI getLayerBoundsUri(String stack,
                                  Double z)
            throws URISyntaxException {
        return new URI(getZUrlString(stack, z) + "/bounds");
    }

    private URI getTileBoundsUri(String stack,
                                 Double z)
            throws URISyntaxException {
        return new URI(getZUrlString(stack, z) + "/tileBounds");
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderDataClient.class);
}

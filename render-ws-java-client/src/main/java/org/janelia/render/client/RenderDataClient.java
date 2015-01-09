package org.janelia.render.client;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.response.JsonResponseHandler;
import org.janelia.render.client.response.ResourceCreatedResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

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

    private String getStackUrlString(String stack)
            throws URISyntaxException {
        return baseDataUrl + "/owner/" + owner + "/project/" + project + "/stack/" + stack;
    }

    private URI getTileUri(String stack,
                           String tileId)
            throws URISyntaxException {
        return new URI(getStackUrlString(stack) + "/tile/" + tileId);
    }

    private URI getResolvedTilesUri(String stack,
                                    Double z)
            throws URISyntaxException {
        return new URI(getStackUrlString(stack) + "/z/" + z + "/resolvedTiles");
    }


    private static final Logger LOG = LoggerFactory.getLogger(RenderDataClient.class);
}

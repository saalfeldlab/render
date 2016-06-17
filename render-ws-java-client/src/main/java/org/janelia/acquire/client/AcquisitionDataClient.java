package org.janelia.acquire.client;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.render.client.request.WaitingRetryHandler;
import org.janelia.render.client.response.EmptyResponseHandler;
import org.janelia.render.client.response.JsonResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP client "wrapper" for accessing the acquisition data (image catcher) web service.
 *
 * @author Eric Trautman
 */
public class AcquisitionDataClient {

    private final String baseUrl;
    private final CloseableHttpClient httpClient;

    /**
     * Creates a new client for the specified owner and project.
     *
     * @param  baseUrl  the base URL string for all requests (e.g. 'http://tem-services:8080/render-ws/v1')
     */
    public AcquisitionDataClient(final String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClientBuilder.create().setRetryHandler(new WaitingRetryHandler()).build();
    }

    @Override
    public String toString() {
        return "{baseUrl='" + baseUrl + "'}";
    }

    /**
     * @param  oldState       acquisition tile must be in this state before retrieval
     * @param  newState       acquisition tile will be in this state after retrieval
     * @param  acquisitionId  if specified, acquisition tile must be associated with this acquisition
     *
     * @return the next tile with the specified state from the acquisition server.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public AcquisitionTile getNextTile(final AcquisitionTileState oldState,
                                       final AcquisitionTileState newState,
                                       final String acquisitionId)
            throws IOException {

        if (oldState.equals(newState)) {
            throw new IllegalArgumentException("oldState and newState are both " + oldState);
        }

        final URIBuilder uriBuilder = new URIBuilder(getUri(baseUrl + "/next-tile"));
        uriBuilder.addParameter("oldState", oldState.toString());
        uriBuilder.addParameter("newState", newState.toString());
        if (acquisitionId != null) {
            uriBuilder.addParameter("acqid", acquisitionId);
        }

        final URI uri = getUri(uriBuilder);
        final HttpPost httpPost = new HttpPost(uri);
        final String requestContext = "POST " + uri;
        final JsonUtils.Helper<AcquisitionTile> helper = new JsonUtils.Helper<>(AcquisitionTile.class);
        final JsonResponseHandler<AcquisitionTile> responseHandler = new JsonResponseHandler<>(requestContext, helper);

        LOG.info("getNextTile: submitting {}", requestContext);

        return httpClient.execute(httpPost, responseHandler);
    }

    /**
     * Updates the state of the specified tiles on the acquisition server.
     *
     * @param  tileIdList  list of tile ids to update.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public void updateTileStates(final AcquisitionTileIdList tileIdList)
            throws IOException {

        final String json = tileIdList.toJson();
        final StringEntity stringEntity = new StringEntity(json, ContentType.APPLICATION_JSON);
        final URI uri = getUri(baseUrl + "/tile-state");
        final String requestContext = "PUT " + uri;
        final EmptyResponseHandler responseHandler = new EmptyResponseHandler(requestContext);

        final HttpPut httpPut = new HttpPut(uri);
        httpPut.setEntity(stringEntity);

        LOG.info("updateTileStates: submitting {} for {} tiles", requestContext, tileIdList.size());

        httpClient.execute(httpPut, responseHandler);
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

    private URI getUri(final URIBuilder uriBuilder)
            throws IOException {
        final URI uri;
        try {
            uri = uriBuilder.build();
        } catch (final URISyntaxException e) {
            throw new IOException("failed to create URI for '" + uriBuilder + "'", e);
        }
        return uri;
    }

    private static final Logger LOG = LoggerFactory.getLogger(AcquisitionDataClient.class);
}

package org.janelia.acquire.client;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.janelia.acquire.client.model.Acquisition;
import org.janelia.acquire.client.model.AcquisitionList;
import org.janelia.acquire.client.model.AcquisitionTileIdList;
import org.janelia.acquire.client.model.AcquisitionTileList;
import org.janelia.acquire.client.model.AcquisitionTileState;
import org.janelia.acquire.client.model.Calibration;
import org.janelia.acquire.client.model.CalibrationList;
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
     * @param  oldState       acquisition tiles must be in this state before retrieval.
     * @param  newState       acquisition tiles will be in this state after retrieval.
     * @param  acquisitionId  if specified, acquisition tiles must be associated with this acquisition.
     * @param  maxTileCount   return data for at most this number of tiles (defaults to 1).
     *
     * @return the next tile with the specified state from the acquisition server.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public AcquisitionTileList getNextTiles(final AcquisitionTileState oldState,
                                            final AcquisitionTileState newState,
                                            final Long acquisitionId,
                                            final Integer maxTileCount)
            throws IOException {

        if (oldState.equals(newState)) {
            throw new IllegalArgumentException("oldState and newState are both " + oldState);
        }

        final URIBuilder uriBuilder = new URIBuilder(getUri(baseUrl + "/next-tile"));
        uriBuilder.addParameter("oldState", oldState.toString());
        uriBuilder.addParameter("newState", newState.toString());

        if (acquisitionId != null) {
            uriBuilder.addParameter("acqid", acquisitionId.toString());
        }

        if (maxTileCount == null) {
            uriBuilder.addParameter("ntiles", "1");
        } else {
            uriBuilder.addParameter("ntiles", maxTileCount.toString());
        }

        final URI uri = getUri(uriBuilder);
        final HttpPost httpPost = new HttpPost(uri);
        final String requestContext = "POST " + uri;
        final JsonUtils.Helper<AcquisitionTileList> helper = new JsonUtils.Helper<>(AcquisitionTileList.class);
        final JsonResponseHandler<AcquisitionTileList> responseHandler = new JsonResponseHandler<>(requestContext, helper);

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

        LOG.info("updateTileStates: submitting {} with {}", requestContext, tileIdList);

        httpClient.execute(httpPut, responseHandler);
    }

    /**
     * @return the list of all calibrations (lens correction data) from the acquisition server.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public List<Calibration> getCalibrations()
            throws IOException {

        final URIBuilder uriBuilder = new URIBuilder(getUri(baseUrl + "/calibrations"));

        final URI uri = getUri(uriBuilder);
        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final JsonUtils.Helper<CalibrationList> helper = new JsonUtils.Helper<>(CalibrationList.class);
        final JsonResponseHandler<CalibrationList> responseHandler = new JsonResponseHandler<>(requestContext, helper);

        LOG.info("getCalibrations: submitting {}", requestContext);

        final CalibrationList calibrationList = httpClient.execute(httpGet, responseHandler);

        return calibrationList.getCalibrations();
    }

    /**
     * @param  tileState      (optional) only return acquisitions with tiles in this state.
     * @param  acquisitionId  (optional) only return the acquisition with this id.
     *
     * @return list of acquisitions that match the specified criteria.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public List<Acquisition> getAcquisitions(final AcquisitionTileState tileState, final Long acquisitionId)
        throws IOException {

        final AcquisitionDataImportClient.Parameters acquisitionFilter = new AcquisitionDataImportClient.Parameters();
        acquisitionFilter.acquisitionId = acquisitionId;
        acquisitionFilter.acquisitionTileState = tileState;
        return getAcquisitionsUsingFilter(acquisitionFilter);
    }

    /**
     * @param  acquisitionFilter acquisition filter parameters for building the qcquisition query
     *
     * @return list of acquisitions that match the specified criteria.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public List<Acquisition> getAcquisitionsUsingFilter(final AcquisitionDataImportClient.Parameters acquisitionFilter)
            throws IOException {

        final URIBuilder uriBuilder = new URIBuilder(getUri(baseUrl + "/acquisitions"));
        if (acquisitionFilter != null) {
            if (acquisitionFilter.acquisitionTileState != null)
                uriBuilder.addParameter("exists-tile-in-state", acquisitionFilter.acquisitionTileState.toString());
            if (acquisitionFilter.acquisitionId != null && acquisitionFilter.acquisitionId > 0)
                uriBuilder.addParameter("acqid", acquisitionFilter.acquisitionId.toString());
            if (acquisitionFilter.projectFilter != null && acquisitionFilter.projectFilter.trim().length() > 0)
                uriBuilder.addParameter("project", acquisitionFilter.projectFilter.trim());
            if (acquisitionFilter.stackFilter != null && acquisitionFilter.stackFilter.trim().length() > 0)
                uriBuilder.addParameter("stack", acquisitionFilter.stackFilter.trim());
            if (acquisitionFilter.mosaicType != null && acquisitionFilter.mosaicType.trim().length() > 0)
                uriBuilder.addParameter("mosaic-type", acquisitionFilter.mosaicType.trim());
            if (acquisitionFilter.acquisitionFromFilter != null && acquisitionFilter.acquisitionFromFilter.trim().length() > 0)
                uriBuilder.addParameter("acq-from", acquisitionFilter.acquisitionFromFilter.trim());
        }

        final URI uri = getUri(uriBuilder);
        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final JsonUtils.Helper<AcquisitionList> helper = new JsonUtils.Helper<>(AcquisitionList.class);
        final JsonResponseHandler<AcquisitionList> responseHandler = new JsonResponseHandler<>(requestContext, helper);

        LOG.info("getAcquisitions: submitting {}", requestContext);

        final AcquisitionList acquisitionList = httpClient.execute(httpGet, responseHandler);

        return acquisitionList.getAcquisitions();
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

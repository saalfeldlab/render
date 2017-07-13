package org.janelia.render.client;

import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Date;
import java.util.List;

import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileCoordinates;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.MipmapPathBuilder;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackVersion;
import org.janelia.render.client.request.WaitingRetryHandler;
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

    private final RenderWebServiceUrls urls;
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
        this.urls = new RenderWebServiceUrls(baseDataUrl, owner, project);
        this.httpClient = HttpClientBuilder.create().setRetryHandler(new WaitingRetryHandler()).build();
    }

    @Override
    public String toString() {
        return String.valueOf(urls);
    }

    /**
     * @return a "likely" unique identifier from the server.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    @SuppressWarnings("UnusedDeclaration")
    public String getLikelyUniqueId()
            throws IOException {

        final URI uri = getUri(urls.getLikelyUniqueIdUrlString());
        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final TextResponseHandler responseHandler = new TextResponseHandler(requestContext);

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
        final JsonUtils.Helper<StackMetaData> helper = new JsonUtils.Helper<>(StackMetaData.class);
        final JsonResponseHandler<StackMetaData> responseHandler = new JsonResponseHandler<>(requestContext, helper);

        LOG.info("getStackMetaData: submitting {}", requestContext);

        return httpClient.execute(httpGet, responseHandler);
    }

    public List<StackId> getOwnerStacks() throws IOException {
        final URI uri = getUri(urls.getOwnerStackIdsUrlString());
        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final TypeReference<List<StackId>> stackIdsTypeReference = new TypeReference<List<StackId>>(){};

        final JsonUtils.GenericHelper<List<StackId>> helper = new JsonUtils.GenericHelper<>(stackIdsTypeReference);
        final JsonResponseHandler<List<StackId>> responseHandler = new JsonResponseHandler<>(requestContext, helper);

        LOG.info("getOwnerStacks: submitting {}", requestContext);

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
        return getStackZValues(stack, null, null);
    }

    /**
     * @param  stack  name of stack.
     * @param  minZ   (optional) minimum value to include in list.
     * @param  maxZ   (optional) maximum value to include in list.
     *
     * @return z values for the specified stack.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public List<Double> getStackZValues(final String stack,
                                        final Double minZ,
                                        final Double maxZ)
            throws IOException {

        final URIBuilder builder = new URIBuilder(getUri(urls.getStackUrlString(stack) + "/zValues"));

        if (minZ != null) {
            builder.addParameter("minZ", minZ.toString());
        }
        if (maxZ != null) {
            builder.addParameter("maxZ", maxZ.toString());
        }

        final URI uri;
        try {
            uri = builder.build();
        } catch (final URISyntaxException e) {
            throw new IOException(e.getMessage(), e);
        }

        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final TypeReference<List<Double>> typeReference = new TypeReference<List<Double>>() {};
        final JsonUtils.GenericHelper<List<Double>> helper = new JsonUtils.GenericHelper<>(typeReference);
        final JsonResponseHandler<List<Double>> responseHandler = new JsonResponseHandler<>(requestContext, helper);

        LOG.info("getStackZValues: submitting {}", requestContext);

        return httpClient.execute(httpGet, responseHandler);
    }

    /**
     * @param  stack  name of stack.
     * @param  minZ   (optional) only include layers with z values greater than or equal to this minimum.
     * @param  maxZ   (optional) only include layers with z values less than or equal to this maximum.
     *
     * @return section data for all layers in the specified stack.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public List<SectionData> getStackSectionData(final String stack,
                                                 final Double minZ,
                                                 final Double maxZ)
            throws IOException {

        final URIBuilder builder = new URIBuilder(getUri(urls.getStackUrlString(stack) + "/sectionData"));

        if (minZ != null) {
            builder.addParameter("minZ", minZ.toString());
        }
        if (maxZ != null) {
            builder.addParameter("maxZ", maxZ.toString());
        }

        final URI uri;
        try {
            uri = builder.build();
        } catch (final URISyntaxException e) {
            throw new IOException(e.getMessage(), e);
        }

        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final TypeReference<List<SectionData>> typeReference = new TypeReference<List<SectionData>>() {};
        final JsonUtils.GenericHelper<List<SectionData>> helper = new JsonUtils.GenericHelper<>(typeReference);
        final JsonResponseHandler<List<SectionData>> responseHandler = new JsonResponseHandler<>(requestContext, helper);

        LOG.info("getStackSectionData: submitting {}", requestContext);

        return httpClient.execute(httpGet, responseHandler);
    }

    /**
     * A derived stack should retain a common set of meta data from its source stack
     * (e.g. resolution and mipmap path builder values).  This method ensures that
     * the derived stack exists, that it shares the common meta data from its source stack,
     * and that it is in the LOADING state.
     *
     * @param  sourceStackMetaData  source stack meta data.
     * @param  derivedStack         name of derived stack.
     *
     * @return meta data for the derived stack.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public StackMetaData setupDerivedStack(final StackMetaData sourceStackMetaData,
                                           final String derivedStack)
            throws IOException {

        final StackVersion sourceVersion = sourceStackMetaData.getCurrentVersion();

        StackMetaData derivedStackMetaData;
        StackVersion derivedVersion;
        try {
            derivedStackMetaData = getStackMetaData(derivedStack);
            derivedVersion = derivedStackMetaData.getCurrentVersion();
        } catch (final Throwable t) {

            LOG.info("setupDerivedStack: derived stack does not exist, creating it ...");

            derivedVersion = new StackVersion(new Date(),
                                              "derived from " + sourceStackMetaData.getStackId(),
                                              null,
                                              null,
                                              sourceVersion.getStackResolutionX(),
                                              sourceVersion.getStackResolutionY(),
                                              sourceVersion.getStackResolutionZ(),
                                              null,
                                              sourceVersion.getMipmapPathBuilder());
            saveStackVersion(derivedStack, derivedVersion);
            derivedStackMetaData = getStackMetaData(derivedStack);
        }

        if ((derivedVersion.getStackResolutionX() == null) ||
            (derivedVersion.getStackResolutionY() == null) ||
            (derivedVersion.getStackResolutionZ() == null)) {

            if ((sourceVersion.getStackResolutionX() != null) ||
                (sourceVersion.getStackResolutionY() != null) ||
                (sourceVersion.getStackResolutionZ() != null)) {

                LOG.info("setupDerivedStack: derived stack is missing resolution data, setting it ...");

                final List<Double> commonResolutionValues = sourceVersion.getStackResolutionValues();
                derivedVersion.setStackResolutionValues(commonResolutionValues);
                setStackResolutionValues(derivedStack, commonResolutionValues);

            }
        }

        final MipmapPathBuilder commonMipmapPathBuilder = sourceVersion.getMipmapPathBuilder();

        if ((derivedVersion.getMipmapPathBuilder() == null) && (commonMipmapPathBuilder != null)) {

            LOG.info("setupDerivedStack: derived stack is missing mipmap path builder, setting it ...");

            derivedVersion.setMipmapPathBuilder(commonMipmapPathBuilder);
            setMipmapPathBuilder(derivedStack, commonMipmapPathBuilder);

        }

        ensureStackIsInLoadingState(derivedStack, derivedStackMetaData);

        return derivedStackMetaData;
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

        final String json = stackVersion.toJson();
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
     * @param  toProject       project for new stack with cloned data (null if same as source project).
     * @param  toStack         new stack to hold cloned data.
     * @param  toStackVersion  version data for the new stack.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public void cloneStackVersion(final String fromStack,
                                  final String toProject,
                                  final String toStack,
                                  final StackVersion toStackVersion,
                                  final Boolean skipTransforms,
                                  final List<Double> zValues)
            throws IOException {

        final String json = toStackVersion.toJson();
        final StringEntity stringEntity = new StringEntity(json, ContentType.APPLICATION_JSON);

        final URIBuilder builder = new URIBuilder(getUri(urls.getCloneToUrlString(fromStack, toStack)));

        if (zValues != null) {
            for (final Double z : zValues) {
                builder.addParameter("z", z.toString());
            }
        }

        if (toProject != null) {
            builder.addParameter("toProject", toProject);
        }

        if (skipTransforms != null) {
            builder.addParameter("skipTransforms", skipTransforms.toString());
        }

        final URI uri;
        try {
            uri = builder.build();
        } catch (final URISyntaxException e) {
            throw new IOException(e.getMessage(), e);
        }

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

        final URI uri = getUri(urls.getStackStateUrlString(stack, stackState));
        final String requestContext = "PUT " + uri;
        final ResourceCreatedResponseHandler responseHandler = new ResourceCreatedResponseHandler(requestContext);

        final HttpPut httpPut = new HttpPut(uri);

        LOG.info("setStackState: submitting {}", requestContext);

        httpClient.execute(httpPut, responseHandler);
    }

    /**
     * Sets the state of the specified stack to LOADING if necessary.
     *
     * @param  stack          stack to change.
     * @param  stackMetaData  current meta data for stack (or null if it needs to be retrieved).
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public void ensureStackIsInLoadingState(final String stack,
                                            StackMetaData stackMetaData)
            throws IOException {

        if (stackMetaData == null) {
            stackMetaData = getStackMetaData(stack);
        }

        if (! stackMetaData.isLoading()) {
            if (! stackMetaData.isReadOnly()) {
                setStackState(stack, StackState.LOADING);
            } else {
                throw new IOException(stack + " stack state is READ_ONLY and cannot be changed");
            }
        } else {
            LOG.info("ensureStackIsInLoadingState: {} stack is already in the LOADING state", stack);
        }

    }

    /**
     * Updates the resolution values for the specified stack.
     *
     * @param  stack             stack to change.
     * @param  resolutionValues  resolution values.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public void setStackResolutionValues(final String stack,
                                         final List<Double> resolutionValues)
            throws IOException {

        final String json = JsonUtils.FAST_MAPPER.writeValueAsString(resolutionValues);
        final StringEntity stringEntity = new StringEntity(json, ContentType.APPLICATION_JSON);
        final URI uri = getUri(urls.getStackUrlString(stack) + "/resolutionValues");
        final String requestContext = "PUT " + uri;
        final TextResponseHandler responseHandler = new TextResponseHandler(requestContext);

        final HttpPut httpPut = new HttpPut(uri);
        httpPut.setEntity(stringEntity);

        LOG.info("setStackResolutionValues: submitting {}", requestContext);

        httpClient.execute(httpPut, responseHandler);
    }

    /**
     * Updates the mipmapPathBuilder for the specified stack.
     *
     * @param  stack              stack to change.
     * @param  mipmapPathBuilder  new builder.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public void setMipmapPathBuilder(final String stack,
                                     final MipmapPathBuilder mipmapPathBuilder)
            throws IOException {

        final String json = mipmapPathBuilder.toJson();
        final StringEntity stringEntity = new StringEntity(json, ContentType.APPLICATION_JSON);
        final URI uri = getUri(urls.getStackUrlString(stack) + "/mipmapPathBuilder");
        final String requestContext = "PUT " + uri;
        final TextResponseHandler responseHandler = new TextResponseHandler(requestContext);

        final HttpPut httpPut = new HttpPut(uri);
        httpPut.setEntity(stringEntity);

        LOG.info("setMipmapPathBuilder: submitting {}", requestContext);

        httpClient.execute(httpPut, responseHandler);
    }

    /**
     * Deletes the specified stack or a layer of the specified stack.
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
            uri = getUri(urls.getZUrlString(stack, z));
        }
        final String requestContext = "DELETE " + uri;
        final TextResponseHandler responseHandler = new TextResponseHandler(requestContext);

        final HttpDelete httpDelete = new HttpDelete(uri);

        LOG.info("deleteStack: submitting {}", requestContext);

        httpClient.execute(httpDelete, responseHandler);
    }

    /**
     * Deletes all tiles with the specified sectionId from the specified stack.
     *
     * @param  stack      stack containing tiles to delete.
     * @param  sectionId  sectionId for all tiles to delete.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public void deleteStackSection(final String stack,
                                   final String sectionId)
            throws IOException {

        final URI uri = getUri(urls.getSectionUrlString(stack, sectionId));
        final String requestContext = "DELETE " + uri;
        final TextResponseHandler responseHandler = new TextResponseHandler(requestContext);

        final HttpDelete httpDelete = new HttpDelete(uri);

        LOG.info("deleteStackSection: submitting {}", requestContext);

        httpClient.execute(httpDelete, responseHandler);
    }

    /**
     * Deletes specified tile.
     *
     * @param  stack   stack containing tile to delete.
     * @param  tileId  id of tile to delete.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public void deleteStackTile(final String stack,
                                final String tileId)
            throws IOException {

        final URI uri = getUri(urls.getTileUrlString(stack, tileId));
        final String requestContext = "DELETE " + uri;
        final TextResponseHandler responseHandler = new TextResponseHandler(requestContext);

        final HttpDelete httpDelete = new HttpDelete(uri);

        LOG.info("deleteStackTile: submitting {}", requestContext);

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

        final URI uri = getUri(urls.getTileUrlString(stack, tileId));
        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final JsonUtils.Helper<TileSpec> helper = new JsonUtils.Helper<>(TileSpec.class);
        final JsonResponseHandler<TileSpec> responseHandler = new JsonResponseHandler<>(requestContext, helper);

        LOG.info("getTile: submitting {}", requestContext);

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

        final URI uri = getUri(urls.getBoundsUrlString(stack, z));
        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final JsonUtils.Helper<Bounds> helper = new JsonUtils.Helper<>(Bounds.class);
        final JsonResponseHandler<Bounds> responseHandler = new JsonResponseHandler<>(requestContext, helper);

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

        final URI uri = getUri(urls.getTileBoundsUrlString(stack, z));
        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final TypeReference<List<TileBounds>> typeReference = new TypeReference<List<TileBounds>>() {};
        final JsonUtils.GenericHelper<List<TileBounds>> helper = new JsonUtils.GenericHelper<>(typeReference);
        final JsonResponseHandler<List<TileBounds>> responseHandler = new JsonResponseHandler<>(requestContext, helper);

        LOG.info("getTileBounds: submitting {}", requestContext);

        return httpClient.execute(httpGet, responseHandler);
    }

    /**
     * Updates the z value for the specified tiles.
     *
     * @param  stack    name of stack.
     * @param  z        new z value for specified tiles.
     * @param  tileIds  list of tiles to update.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public void updateZForTiles(final String stack,
                                final Double z,
                                final List<String> tileIds)
            throws IOException {

        final String json = JsonUtils.FAST_MAPPER.writeValueAsString(tileIds);
        final StringEntity stringEntity = new StringEntity(json, ContentType.APPLICATION_JSON);
        final URI uri = getUri(urls.getTileIdsUrlString(stack, z));
        final String requestContext = "PUT " + uri;
        final ResourceCreatedResponseHandler responseHandler = new ResourceCreatedResponseHandler(requestContext);

        final HttpPut httpPut = new HttpPut(uri);
        httpPut.setEntity(stringEntity);

        LOG.info("updateZForTiles: submitting {} for {} tileIds",
                 requestContext, tileIds.size());

        httpClient.execute(httpPut, responseHandler);
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
        final JsonUtils.Helper<ResolvedTileSpecCollection> helper =
                new JsonUtils.Helper<>(ResolvedTileSpecCollection.class);
        final JsonResponseHandler<ResolvedTileSpecCollection> responseHandler =
                new JsonResponseHandler<>(requestContext, helper);

        LOG.info("getResolvedTiles: submitting {}", requestContext);

        return httpClient.execute(httpGet, responseHandler);
    }

    /**
     * @param  stack    name of stack.
     * @param  minZ     minimum z value for all tiles (or null for no minimum).
     * @param  maxZ     maximum z value for all tiles (or null for no maximum).
     * @param  groupId  group id for all tiles (or null).
     * @param  minX     minimum x value for all tiles (or null for no minimum).
     * @param  maxX     maximum x value for all tiles (or null for no maximum).
     * @param  minY     minimum y value for all tiles (or null for no minimum).
     * @param  maxY     maximum y value for all tiles (or null for no maximum).
     *
     * @return the set of resolved tiles and transforms that match the specified criteria.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public ResolvedTileSpecCollection getResolvedTiles(final String stack,
                                                       final Double minZ,
                                                       final Double maxZ,
                                                       final String groupId,
                                                       final Double minX,
                                                       final Double maxX,
                                                       final Double minY,
                                                       final Double maxY)
            throws IOException {

        final URIBuilder uriBuilder = new URIBuilder(getResolvedTilesUri(stack, null));
        addParameterIfDefined("minZ", minZ, uriBuilder);
        addParameterIfDefined("maxZ", maxZ, uriBuilder);
        addParameterIfDefined("groupId", groupId, uriBuilder);
        addParameterIfDefined("minX", minX, uriBuilder);
        addParameterIfDefined("maxX", maxX, uriBuilder);
        addParameterIfDefined("minY", minY, uriBuilder);
        addParameterIfDefined("maxY", maxY, uriBuilder);

        final URI uri = getUri(uriBuilder);
        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final JsonUtils.Helper<ResolvedTileSpecCollection> helper =
                new JsonUtils.Helper<>(ResolvedTileSpecCollection.class);
        final JsonResponseHandler<ResolvedTileSpecCollection> responseHandler =
                new JsonResponseHandler<>(requestContext, helper);

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

        final String json = resolvedTiles.toJson();
        final StringEntity stringEntity = new StringEntity(json, ContentType.APPLICATION_JSON);
        final URI uri = getResolvedTilesUri(stack, z);
        final String requestContext = "PUT " + uri;
        final ResourceCreatedResponseHandler responseHandler = new ResourceCreatedResponseHandler(requestContext);

        final HttpPut httpPut = new HttpPut(uri);
        httpPut.setEntity(stringEntity);

        LOG.info("saveResolvedTiles: submitting {} for {} transforms and {} tiles",
                 requestContext, resolvedTiles.getTransformCount(), resolvedTiles.getTileCount());

        httpClient.execute(httpPut, responseHandler);
    }

    /**
     * Updates the z value for the specified stack section.
     *
     * @param  stack          name of stack.
     * @param  sectionId      identifier for section.
     * @param  z              z value for section.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public void updateZForSection(final String stack,
                                  final String sectionId,
                                  final Double z)
            throws IOException {

        final String json = z.toString();
        final StringEntity stringEntity = new StringEntity(json, ContentType.APPLICATION_JSON);
        final URI uri = getUri(urls.getSectionZUrlString(stack, sectionId));
        final String requestContext = "PUT " + uri;
        final ResourceCreatedResponseHandler responseHandler = new ResourceCreatedResponseHandler(requestContext);

        final HttpPut httpPut = new HttpPut(uri);
        httpPut.setEntity(stringEntity);

        LOG.info("updateZForSection: submitting {}", requestContext);

        httpClient.execute(httpPut, responseHandler);
    }

    /**
     * @param  pGroupId      pGroupId (usually the section id).
     *
     * @return list of canvas matches with the specified pGroupId.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public List<CanvasMatches> getMatchesWithPGroupId(final String pGroupId)
            throws IOException {

        final URI uri = getUri(urls.getMatchesWithPGroupIdUrlString(pGroupId));
        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final TypeReference<List<CanvasMatches>> typeReference = new TypeReference<List<CanvasMatches>>() {};
        final JsonUtils.GenericHelper<List<CanvasMatches>> helper = new JsonUtils.GenericHelper<>(typeReference);
        final JsonResponseHandler<List<CanvasMatches>> responseHandler = new JsonResponseHandler<>(requestContext, helper);

        LOG.info("getMatches: submitting {}", requestContext);

        return httpClient.execute(httpGet, responseHandler);
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

        if (canvasMatches.size() > 0) {

            final String json = JsonUtils.MAPPER.writeValueAsString(canvasMatches);
            final StringEntity stringEntity = new StringEntity(json, ContentType.APPLICATION_JSON);
            final URI uri = getUri(urls.getMatchesUrlString());
            final String requestContext = "PUT " + uri;
            final ResourceCreatedResponseHandler responseHandler = new ResourceCreatedResponseHandler(requestContext);

            final HttpPut httpPut = new HttpPut(uri);
            httpPut.setEntity(stringEntity);

            LOG.info("saveMatches: submitting {} for {} pair(s)", requestContext, canvasMatches.size());

            httpClient.execute(httpPut, responseHandler);

        } else {
            LOG.info("saveMatches: no matches to save");
        }
    }

    /**
     * @return list of tile specs with the specified ids.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public List<TileSpec> getTileSpecsWithIds(final List<String> tileIdList,
                                              final String stack)
            throws IOException {

        final String tileIdListJson = JsonUtils.MAPPER.writeValueAsString(tileIdList);
        final StringEntity stringEntity = new StringEntity(tileIdListJson, ContentType.APPLICATION_JSON);
        final URI uri = getUri(urls.getStackUrlString(stack) + "/tile-specs-with-ids");
        final String requestContext = "PUT " + uri;

        final HttpPut httpPut = new HttpPut(uri);
        httpPut.setEntity(stringEntity);

        final TypeReference<List<TileSpec>> typeReference =
                new TypeReference<List<TileSpec>>() {};
        final JsonUtils.GenericHelper<List<TileSpec>> helper =
                new JsonUtils.GenericHelper<>(typeReference);
        final JsonResponseHandler<List<TileSpec>> responseHandler =
                new JsonResponseHandler<>(requestContext, helper);

        LOG.info("getTileSpecsWithIds: submitting {}", requestContext);

        return httpClient.execute(httpPut, responseHandler);
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

        final String worldCoordinatesJson = JsonUtils.MAPPER.writeValueAsString(worldCoordinates);
        final StringEntity stringEntity = new StringEntity(worldCoordinatesJson, ContentType.APPLICATION_JSON);
        final URI uri = getUri(urls.getTileIdsForCoordinatesUrlString(stack, z));
        final String requestContext = "PUT " + uri;

        final HttpPut httpPut = new HttpPut(uri);
        httpPut.setEntity(stringEntity);

        final TypeReference<List<List<TileCoordinates>>> typeReference =
                new TypeReference<List<List<TileCoordinates>>>() {};
        final JsonUtils.GenericHelper<List<List<TileCoordinates>>> helper =
                new JsonUtils.GenericHelper<>(typeReference);
        final JsonResponseHandler<List<List<TileCoordinates>>> responseHandler =
                new JsonResponseHandler<>(requestContext, helper);

        LOG.info("getTileIdsForCoordinates: submitting {}", requestContext);

        return httpClient.execute(httpPut, responseHandler);
    }

    /**
     * @return a render parameters URL string composed from the specified values.
     */
    public String getRenderParametersUrlString(final String stack,
                                               final double x,
                                               final double y,
                                               final double z,
                                               final int width,
                                               final int height,
                                               final double scale) {
        return urls.getRenderParametersUrlString(stack, x, y, z, width, height, scale);
    }

    private URI getStackUri(final String stack)
            throws IOException {
        return getUri(urls.getStackUrlString(stack));
    }

    private URI getResolvedTilesUri(final String stack,
                                    final Double z)
            throws IOException {
        final String baseUrlString;
        if (z == null) {
            baseUrlString = urls.getStackUrlString(stack);
        } else {
            baseUrlString = urls.getZUrlString(stack, z);
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

    private void addParameterIfDefined(final String name,
                                       final Object value,
                                       final URIBuilder uriBuilder) {
        if (value != null) {
            uriBuilder.addParameter(name, String.valueOf(value));
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderDataClient.class);
}

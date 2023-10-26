package org.janelia.render.client;

import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.MatchCollectionMetaData;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.ResolvedTileSpecsWithMatchPairs;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileCoordinates;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.MipmapPathBuilder;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackMetaData.StackState;
import org.janelia.alignment.spec.stack.StackVersion;
import org.janelia.alignment.util.RenderWebServiceUrls;
import org.janelia.alignment.util.ZFilter;
import org.janelia.render.client.request.WaitingRetryHandler;
import org.janelia.render.client.response.BaseResponseHandler;
import org.janelia.render.client.response.EmptyResponseHandler;
import org.janelia.render.client.response.JsonResponseHandler;
import org.janelia.render.client.response.ResourceCreatedResponseHandler;
import org.janelia.render.client.response.TextResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP client "wrapper" for retrieving and storing render data via the render web service.
 *
 * @author Eric Trautman
 */
@SuppressWarnings("JavadocLinkAsPlainText")
public class RenderDataClient {

    private final String project;
    private final RenderWebServiceUrls urls;
    private final CloseableHttpClient httpClient;

    /**
     * Creates a new client for the specified owner and project.
     *
     * @param  baseDataUrl  the base URL string for all requests (e.g. 'http://em-services-1:8080/render-ws/v1')
     * @param  owner        the owner name for all requests.
     * @param  project      the project name for all requests.
     */
    public RenderDataClient(final String baseDataUrl,
                            final String owner,
                            final String project) {
        this(project,
             new RenderWebServiceUrls(baseDataUrl, owner, project),
             HttpClientBuilder.create().setRetryHandler(new WaitingRetryHandler()).build());
    }

    public RenderDataClient(final String project,
                            final RenderWebServiceUrls urls,
                            final CloseableHttpClient httpClient) {
        this.project = project;
        this.urls = urls;
        this.httpClient = httpClient;
    }

    public String getOwner() {
        return urls.getOwner();
    }

    public String getProject() {
        return project;
    }

    public RenderWebServiceUrls getUrls() {
        return urls;
    }

    public String getBaseDataUrl() {
        return urls.getBaseDataUrl();
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
     * @return metadata for the specified stack.
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
        return getStackIds(null);
    }

    public List<StackId> getProjectStacks() throws IOException {
        return getStackIds(project);
    }

    public List<StackId> getStackIds(final String project) throws IOException {
        final URI uri = getUri(urls.getStackIdsUrlString(project));
        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final TypeReference<List<StackId>> stackIdsTypeReference = new TypeReference<>(){};

        final JsonUtils.GenericHelper<List<StackId>> helper = new JsonUtils.GenericHelper<>(stackIdsTypeReference);
        final JsonResponseHandler<List<StackId>> responseHandler = new JsonResponseHandler<>(requestContext, helper);

        LOG.info("getStackIds: submitting {}", requestContext);

        return httpClient.execute(httpGet, responseHandler);
    }

    public List<MatchCollectionMetaData> getOwnerMatchCollections() throws IOException {
        final URI uri = getUri(urls.getOwnerMatchCollectionsUrlString());
        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final TypeReference<List<MatchCollectionMetaData>> collectionsTypeReference = new TypeReference<>() {};

        final JsonUtils.GenericHelper<List<MatchCollectionMetaData>> helper =
                new JsonUtils.GenericHelper<>(collectionsTypeReference);
        final JsonResponseHandler<List<MatchCollectionMetaData>> responseHandler =
                new JsonResponseHandler<>(requestContext, helper);

        LOG.info("getOwnerMatchCollections: submitting {}", requestContext);

        return httpClient.execute(httpGet, responseHandler);
    }

    /**
     * Deletes the specified match collection.
     *
     * @param  collectionName  collection to delete (or null to use this client's project as the collection name).
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public void deleteMatchCollection(final String collectionName)
            throws IOException {

        final URI uri;
        if (collectionName == null) {
            uri = getUri(urls.getMatchCollectionUrlString());
        } else {
            uri = getUri(urls.getOwnerUrlString() + "/matchCollection/" + collectionName);
        }
        final String requestContext = "DELETE " + uri;
        final TextResponseHandler responseHandler = new TextResponseHandler(requestContext);

        final HttpDelete httpDelete = new HttpDelete(uri);

        LOG.info("deleteMatchCollection: submitting {}", requestContext);

        httpClient.execute(httpDelete, responseHandler);
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
        final URI uri = getUriWithZRangeParameters(minZ, maxZ, builder);

        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final TypeReference<List<Double>> typeReference = new TypeReference<>() {
        };
        final JsonUtils.GenericHelper<List<Double>> helper = new JsonUtils.GenericHelper<>(typeReference);
        final JsonResponseHandler<List<Double>> responseHandler = new JsonResponseHandler<>(requestContext, helper);

        LOG.info("getStackZValues: submitting {}", requestContext);

        return httpClient.execute(httpGet, responseHandler);
    }

    /**
     * @param  stack            name of stack.
     * @param  minZ             (optional) minimum value to include in list.
     * @param  maxZ             (optional) maximum value to include in list.
     * @param  explicitZValues  (optional) collection of z values to explicitly include.
     *
     * @return z values for the specified stack.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public List<Double> getStackZValues(final String stack,
                                        final Double minZ,
                                        final Double maxZ,
                                        final Collection<Double> explicitZValues)
            throws IOException {

        final List<Double> zList;

        if ((explicitZValues == null) || (explicitZValues.isEmpty())) {

            zList = getStackZValues(stack, minZ, maxZ);

        } else {

            final ZFilter zFilter = new ZFilter(minZ, maxZ, explicitZValues);
            final List<Double> allZList = getStackZValues(stack);
            zList = new ArrayList<>(allZList.size());
            zList.addAll(
                    allZList.stream()
                            .filter(zFilter::accept)
                            .collect(Collectors.toList()));

            LOG.info("getStackZValues: returning values for {} (filtered) layers", zList.size());
        }

        return zList;
    }

    /**
     * @param  stack  name of stack.
     * @param  minZ   (optional) only include layers with z values greater than or equal to this minimum.
     * @param  maxZ   (optional) only include layers with z values less than or equal to this maximum.
     *
     * @return section data for set of layers in the specified stack.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public List<SectionData> getStackSectionData(final String stack,
                                                 final Double minZ,
                                                 final Double maxZ)
            throws IOException {

        final URIBuilder builder = new URIBuilder(getUri(urls.getStackUrlString(stack) + "/sectionData"));
        final URI uri = getUriWithZRangeParameters(minZ, maxZ, builder);

        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final TypeReference<List<SectionData>> typeReference = new TypeReference<>() {
        };
        final JsonUtils.GenericHelper<List<SectionData>> helper = new JsonUtils.GenericHelper<>(typeReference);
        final JsonResponseHandler<List<SectionData>> responseHandler = new JsonResponseHandler<>(requestContext, helper);

        LOG.info("getStackSectionData: submitting {}", requestContext);

        return httpClient.execute(httpGet, responseHandler);
    }

    /**
     * @param  stack            name of stack.
     * @param  minZ             (optional) only include layers with z values greater than or equal to this minimum.
     * @param  maxZ             (optional) only include layers with z values less than or equal to this maximum.
     * @param  explicitZValues  (optional) collection of z values to explicitly include.
     *
     * @return section data for set of layers in the specified stack.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public List<SectionData> getStackSectionData(final String stack,
                                                 final Double minZ,
                                                 final Double maxZ,
                                                 final Collection<Double> explicitZValues)
            throws IOException {

        final List<SectionData> sectionDataList;

        if ((explicitZValues == null) || (explicitZValues.isEmpty())) {

            sectionDataList = getStackSectionData(stack, minZ, maxZ);

        } else {

            final ZFilter zFilter = new ZFilter(minZ, maxZ, explicitZValues);
            final List<SectionData> allSectionDataList = getStackSectionData(stack, null, null);
            sectionDataList = new ArrayList<>(allSectionDataList.size());
            sectionDataList.addAll(
                    allSectionDataList.stream()
                            .filter(sectionData -> zFilter.accept(sectionData.getZ()))
                            .collect(Collectors.toList()));

            LOG.info("getStackSectionData: returning data for {} (filtered) sections", sectionDataList.size());
        }

        return sectionDataList;
    }

    // TODO: look for clients that have this logic and replace with call to this method

    /**
     * @param  stack            name of stack.
     * @param  minZ             (optional) only include layers with z values greater than or equal to this minimum.
     * @param  maxZ             (optional) only include layers with z values less than or equal to this maximum.
     * @param  explicitZValues  (optional) collection of z values to explicitly include.
     *
     * @return section data for set of layers in the specified stack mapped to z.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public Map<Double, Set<String>> getStackZToSectionIdsMap(final String stack,
                                                             final Double minZ,
                                                             final Double maxZ,
                                                             final Collection<Double> explicitZValues)
            throws IOException {

        final List<SectionData> sectionDataList = getStackSectionData(stack, minZ, maxZ, explicitZValues);

        final Map<Double, Set<String>> zToSectionIdsMap = new HashMap<>(sectionDataList.size());
        sectionDataList.forEach(sd -> {
            final Double z = sd.getZ();
            if (z != null) {
                final Set<String> sectionIdsForZ = zToSectionIdsMap.computeIfAbsent(z,
                                                                                    sectionIdSet -> new HashSet<>());
                sectionIdsForZ.add(sd.getSectionId());
            }
        });

        return zToSectionIdsMap;
    }

    /**
     * A derived stack should retain a common set of metadata from its source stack
     * (e.g. resolution and mipmap path builder values).  This method ensures that
     * the derived stack exists, that it shares the common metadata from its source stack,
     * and that it is in the LOADING state.
     *
     * @param  sourceStackMetaData  source stack meta data.
     * @param  derivedStack         name of derived stack.
     *
     * @return metadata for the derived stack.
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
     * Renames the specified stack.
     *
     * @param  fromStack       source stack to rename.
     * @param  toStackId       new owner, project, and/or stack names.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public void renameStack(final String fromStack,
                            final StackId toStackId)
            throws IOException {

        final String json = toStackId.toJson();
        final StringEntity stringEntity = new StringEntity(json, ContentType.APPLICATION_JSON);

        final URI uri = getUri(urls.getStackUrlString(fromStack) + "/stackId");

        final String requestContext = "PUT " + uri;
        final EmptyResponseHandler responseHandler = new EmptyResponseHandler(requestContext);

        final HttpPut httpPut = new HttpPut(uri);
        httpPut.setEntity(stringEntity);

        LOG.info("renameStack: submitting {} with body {}", requestContext, json);

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
     * @param  stackMetaData  current metadata for stack (or null if it needs to be retrieved).
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
     * Deletes the mipmap path builder for the specified stack.
     */
    public void deleteMipmapPathBuilder(final String stack)
            throws IOException {

        final URI uri = getUri(urls.getStackUrlString(stack) + "/mipmapPathBuilder");
        final String requestContext = "DELETE " + uri;
        final TextResponseHandler responseHandler = new TextResponseHandler(requestContext);

        final HttpDelete httpDelete = new HttpDelete(uri);

        LOG.info("deleteMipmapPathBuilder: submitting {}", requestContext);

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
        final TypeReference<List<TileBounds>> typeReference = new TypeReference<>() {
        };
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
     * @return render parameters (with flattened tile specs) for the specified layer.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public RenderParameters getRenderParametersForZ(final String stack,
                                                    final Double z)
            throws IOException {

        final URI uri = getUri(urls.getZUrlString(stack, z) + "/render-parameters");
        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final JsonUtils.Helper<RenderParameters> helper = new JsonUtils.Helper<>(RenderParameters.class);
        final JsonResponseHandler<RenderParameters> responseHandler = new JsonResponseHandler<>(requestContext, helper);

        LOG.info("getRenderParametersForZ: submitting {}", requestContext);

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
        return getResolvedTiles(stack, z, null);
    }

    /**
     * @param  stack         name of stack.
     * @param  z             z value for layer.
     * @param  matchPattern  (optional) if specified, only return tiles with ids that match this pattern.
     *
     * @return the set of resolved tiles and transforms for the specified layer.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public ResolvedTileSpecCollection getResolvedTiles(final String stack,
                                                       final Double z,
                                                       final String matchPattern)
            throws IOException {

        final URIBuilder uriBuilder = new URIBuilder(getResolvedTilesUri(stack, z));
        addParameterIfDefined("matchPattern", matchPattern, uriBuilder);
        final URI uri = getUri(uriBuilder);
        return getResolvedTileSpecCollection(uri);
    }

    /**
     * @param  stack    name of stack.
     * @param  minZ     minimum z value for all tiles (or null for no minimum).
     * @param  maxZ     maximum z value for all tiles (or null for no maximum).
     *
     * @return the set of resolved tiles and transforms that match the specified criteria.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public ResolvedTileSpecCollection getResolvedTilesForZRange(final String stack,
                                                                final Double minZ,
                                                                final Double maxZ)
            throws IOException {

        final URIBuilder uriBuilder = new URIBuilder(getResolvedTilesUri(stack, null));
        addParameterIfDefined("minZ", minZ, uriBuilder);
        addParameterIfDefined("maxZ", maxZ, uriBuilder);
        final URI uri = getUri(uriBuilder);
        return getResolvedTileSpecCollection(uri);
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
     * @param  matchPattern  only return tiles with ids that match this pattern (null for all tiles).
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
                                                       final Double maxY,
                                                       final String matchPattern)
            throws IOException {

        final URIBuilder uriBuilder = new URIBuilder(getResolvedTilesUri(stack, null));
        addParameterIfDefined("minZ", minZ, uriBuilder);
        addParameterIfDefined("maxZ", maxZ, uriBuilder);
        addParameterIfDefined("groupId", groupId, uriBuilder);
        addParameterIfDefined("minX", minX, uriBuilder);
        addParameterIfDefined("maxX", maxX, uriBuilder);
        addParameterIfDefined("minY", minY, uriBuilder);
        addParameterIfDefined("maxY", maxY, uriBuilder);
        addParameterIfDefined("matchPattern", matchPattern, uriBuilder);
        final URI uri = getUri(uriBuilder);
        return getResolvedTileSpecCollection(uri);
    }

    /**
     * @param  stack           name of stack.
     * @param  bounds          optional bounds values for all tiles (null to exclude bounds).
     * @param  collectionName  name of match collection.
     * @param  groupId         group id for all tiles (or null).
     * @param  matchPattern    only return tiles with ids that match this pattern (null for all tiles).
     * @param  handleNotFound  if true, return empty object when 404 is returned for request instead of just raising exception.
     *
     * @return the set of resolved tiles and transforms that match the specified criteria.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public ResolvedTileSpecsWithMatchPairs getResolvedTilesWithMatchPairs(final String stack,
                                                                          final Bounds bounds,
                                                                          final String collectionName,
                                                                          final String groupId,
                                                                          final String matchPattern,
                                                                          final boolean handleNotFound)
            throws IOException {

        final String baseUrlString = urls.getStackUrlString(stack);
        final URI baseUri =  getUri(baseUrlString + "/resolvedTilesWithMatchesFrom/" + collectionName);

        final URIBuilder uriBuilder = new URIBuilder(baseUri);
        if (bounds != null) {
            addParameterIfDefined("minX", bounds.getMinX(), uriBuilder);
            addParameterIfDefined("maxX", bounds.getMaxX(), uriBuilder);
            addParameterIfDefined("minY", bounds.getMinY(), uriBuilder);
            addParameterIfDefined("maxY", bounds.getMaxY(), uriBuilder);
            addParameterIfDefined("minZ", bounds.getMinZ(), uriBuilder);
            addParameterIfDefined("maxZ", bounds.getMaxZ(), uriBuilder);
        }
        addParameterIfDefined("groupId", groupId, uriBuilder);
        addParameterIfDefined("matchPattern", matchPattern, uriBuilder);

        final URI uri = getUri(uriBuilder);

        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final JsonUtils.Helper<ResolvedTileSpecsWithMatchPairs> helper =
                new JsonUtils.Helper<>(ResolvedTileSpecsWithMatchPairs.class);
        final JsonResponseHandler<ResolvedTileSpecsWithMatchPairs> responseHandler =
                new JsonResponseHandler<>(requestContext, helper);

        LOG.info("getResolvedTilesWithMatchPairs: submitting {}", requestContext);

        ResolvedTileSpecsWithMatchPairs result;
        try {
            result = httpClient.execute(httpGet, responseHandler);
        } catch (final IOException e) {
            final String msg = e.getMessage();
            if (handleNotFound && (msg != null) && msg.contains(EXCEPTION_MSG_PREFIX_FOR_404)) {
                LOG.info("getResolvedTilesWithMatchPairs: handling request exception: {}", msg);
                result = new ResolvedTileSpecsWithMatchPairs(new ResolvedTileSpecCollection(new ArrayList<>(),
                                                                                            new ArrayList<>()),
                                                             new ArrayList<>());
            } else {
                throw e;
            }
        }

        return result;
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
     * @return list of pGroup identifiers for this collection.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public List<String> getMatchPGroupIds()
            throws IOException {
        final URI uri = getUri(urls.getMatchCollectionUrlString() + "/pGroupIds");
        return submitGetForStringArray("getMatchPGroupIds", uri);
    }

    /**
     * Updates match counts for all pairs with specified pGroup.
     *
     * @param  pGroupId  pGroupId.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public void updateMatchCountsForPGroup(final String pGroupId)
            throws IOException {

        final URI uri = getUri(urls.getMatchCollectionUrlString() + "/pGroup/" + pGroupId + "/matchCounts");
        final String requestContext = "PUT " + uri;
        final ResourceCreatedResponseHandler responseHandler = new ResourceCreatedResponseHandler(requestContext);

        final HttpPut httpPut = new HttpPut(uri);

        LOG.info("updateMatchCountsForPGroup: submitting {}", requestContext);

        httpClient.execute(httpPut, responseHandler);
    }

    /**
     *
     * @param pGroupId  first tile's section id.
     * @param pId       first tile's id.
     * @param qGroupId  second tile's section id.
     * @param qId       second tile's id.
     *
     * @return canvas matches between the specified tiles.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public CanvasMatches getMatchesBetweenTiles(final String pGroupId,
                                                final String pId,
                                                final String qGroupId,
                                                final String qId)
            throws IOException {

        final String urlString = String.format("%s/group/%s/id/%s/matchesWith/%s/id/%s",
                                               urls.getMatchCollectionUrlString(), pGroupId, pId, qGroupId, qId);
        final List<CanvasMatches> responseList = getMatches("getMatchesBetweenTiles",
                                                            urlString,
                                                            false);
        CanvasMatches canvasMatches = null;
        if (responseList.size() == 1) {
            canvasMatches = responseList.get(0);
        } else if (responseList.size() > 1) {
            throw new IOException(responseList.size() + " match records returned for pId " + pId +
                                  " and qId " + qId + " when there should only be one record");
        }

        return canvasMatches;
    }

    /**
     * @param  pGroupId             pGroupId (usually the section id).
     * @param  excludeMatchDetails  if true, only retrieve pair identifiers and exclude detailed match points.
     *
     * @return list of canvas matches with the specified pGroupId.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public List<CanvasMatches> getMatchesWithPGroupId(final String pGroupId,
                                                      final boolean excludeMatchDetails)
            throws IOException {
        return getMatches("getMatchesWithPGroupId",
                          urls.getMatchesWithPGroupIdUrlString(pGroupId),
                          excludeMatchDetails);
    }


    /**
     * @param  groupId      groupId (usually the section id).
     *
     * @return list of canvas matches between the specified groupId
     *         and all other canvases that have the same groupId.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public List<CanvasMatches> getMatchesWithinGroup(final String groupId)
            throws IOException {
        return getMatchesWithinGroup(groupId, false);
    }

    /**
     * @param  groupId              groupId (usually the section id).
     * @param  excludeMatchDetails  if true, only retrieve pair identifiers and exclude detailed match points.
     *
     * @return list of canvas matches between the specified groupId
     *         and all other canvases that have the same groupId.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public List<CanvasMatches> getMatchesWithinGroup(final String groupId,
                                                     final boolean excludeMatchDetails)
            throws IOException {

        return getMatches("getMatchesWithinGroup",
                          urls.getMatchesWithinGroupUrlString(groupId),
                          excludeMatchDetails);
    }

    /**
     *
     * @param  pGroupId             first tile's section id.
     * @param  qGroupId             second tile's section id.
     * @param  excludeMatchDetails  if true, only retrieve pair identifiers and exclude detailed match points.
     *
     * @return list of canvas matches between the specified groups.
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public List<CanvasMatches> getMatchesBetweenGroups(final String pGroupId,
                                                       final String qGroupId,
                                                       final boolean excludeMatchDetails)
            throws IOException {

        final String urlString = String.format("%s/group/%s/matchesWith/%s",
                                               urls.getMatchCollectionUrlString(), pGroupId, qGroupId);
        return getMatches("getMatchesBetweenGroups",
                          urlString,
                          excludeMatchDetails);
    }

    /**
     * Deletes matches with the specified pGroupId.
     *
     * @param  pGroupId             pGroupId (usually the section id).
     *
     * @throws IOException
     *   if the request fails for any reason.
     */
    public void deleteMatchesWithPGroupId(final String pGroupId)
            throws IOException {

        final URI uri = getUri(urls.getMatchesWithPGroupIdUrlString(pGroupId));
        final String requestContext = "DELETE " + uri;
        final TextResponseHandler responseHandler = new TextResponseHandler(requestContext);

        final HttpDelete httpDelete = new HttpDelete(uri);

        LOG.info("deleteMatchesWithPGroupId: submitting {}", requestContext);

        httpClient.execute(httpDelete, responseHandler);
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

        if (!canvasMatches.isEmpty()) {

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
                new TypeReference<>() {
                };
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
     * <br/>
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
                new TypeReference<>() {
                };
        final JsonUtils.GenericHelper<List<List<TileCoordinates>>> helper =
                new JsonUtils.GenericHelper<>(typeReference);
        final JsonResponseHandler<List<List<TileCoordinates>>> responseHandler =
                new JsonResponseHandler<>(requestContext, helper);

        LOG.info("getTileIdsForCoordinates: submitting {}", requestContext);

        return httpClient.execute(httpPut, responseHandler);
    }

    /**
     * @return a render parameters URL string composed of the specified values.
     */
    public String getRenderParametersUrlString(final String stack,
                                               final double x,
                                               final double y,
                                               final double z,
                                               final int width,
                                               final int height,
                                               final double scale,
                                               final String filterListName) {
        return urls.getRenderParametersUrlString(stack, x, y, z, width, height, scale, filterListName);
    }

    public RenderDataClient buildClientForProject(final String project) {
        return buildClient(this.urls.getOwner(), project);
    }

    public RenderDataClient buildClient(final String owner,
                                        final String project) {
        RenderDataClient clonedClient = this;
        if ((! this.project.equals(project)) || (! this.urls.getOwner().equals(owner))) {
            clonedClient = new RenderDataClient(project,
                                                new RenderWebServiceUrls(this.urls.getBaseDataUrl(), owner, project),
                                                httpClient);
        }
        return clonedClient;
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

    private static URI getUri(final String forString)
            throws IOException {
        final URI uri;
        try {
            uri = new URI(forString);
        } catch (final URISyntaxException e) {
            throw new IOException("failed to create URI for '" + forString + "'", e);
        }
        return uri;
    }

    private static URI getUri(final URIBuilder uriBuilder)
            throws IOException {
        final URI uri;
        try {
            uri = uriBuilder.build();
        } catch (final URISyntaxException e) {
            throw new IOException("failed to create URI for '" + uriBuilder + "'", e);
        }
        return uri;
    }

    private static URI getUriWithZRangeParameters(final Double minZ,
                                                  final Double maxZ,
                                                  final URIBuilder builder)
            throws IOException {
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
        return uri;
    }

    private void addParameterIfDefined(final String name,
                                       final Object value,
                                       final URIBuilder uriBuilder) {
        if (value != null) {
            uriBuilder.addParameter(name, String.valueOf(value));
        }
    }

    private ResolvedTileSpecCollection getResolvedTileSpecCollection(final URI uri)
            throws IOException {
        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final JsonUtils.Helper<ResolvedTileSpecCollection> helper =
                new JsonUtils.Helper<>(ResolvedTileSpecCollection.class);
        final JsonResponseHandler<ResolvedTileSpecCollection> responseHandler =
                new JsonResponseHandler<>(requestContext, helper);

        LOG.info("getResolvedTileSpecCollection: submitting {}", requestContext);

        return httpClient.execute(httpGet, responseHandler);
    }

    private List<CanvasMatches> getMatches(final String context,
                                           final String urlString,
                                           final boolean excludeMatchDetails)
            throws IOException {
        final URI uri;
        try {
            final URIBuilder builder = new URIBuilder(urlString);
            if (excludeMatchDetails) {
                builder.addParameter("excludeMatchDetails", "true");
            }
            uri = builder.build();
        } catch (final URISyntaxException e) {
            throw new IOException(e.getMessage(), e);
        }

        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final TypeReference<List<CanvasMatches>> typeReference = new TypeReference<>() {};
        final JsonUtils.GenericHelper<List<CanvasMatches>> helper = new JsonUtils.GenericHelper<>(typeReference);
        final JsonResponseHandler<List<CanvasMatches>> responseHandler = new JsonResponseHandler<>(requestContext,
                                                                                                   helper);

        LOG.info(context + ": submitting {}", requestContext);

        return httpClient.execute(httpGet, responseHandler);
    }

    @SuppressWarnings("SameParameterValue")
    private List<String> submitGetForStringArray(final String logContext,
                                                 final URI uri)
            throws IOException {

        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final TypeReference<List<String>> typeReference = new TypeReference<>() {};
        final JsonUtils.GenericHelper<List<String>> helper = new JsonUtils.GenericHelper<>(typeReference);
        final JsonResponseHandler<List<String>> responseHandler = new JsonResponseHandler<>(requestContext, helper);

        LOG.info("{}: submitting {}", logContext, requestContext);

        return httpClient.execute(httpGet, responseHandler);
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderDataClient.class);

    private static final String EXCEPTION_MSG_PREFIX_FOR_404 =
            BaseResponseHandler.buildHttpStatusMessagePrefix(404);

}

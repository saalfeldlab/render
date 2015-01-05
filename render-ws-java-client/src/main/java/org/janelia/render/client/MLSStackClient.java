package org.janelia.render.client;

import mpicbg.trakem2.transform.MovingLeastSquaresTransform2;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.janelia.alignment.MovingLeastSquaresBuilder;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.render.client.response.ResolvedTilesResponseHandler;
import org.janelia.render.client.response.ResourceCreatedResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;

/**
 * Java client for generating Moving Least Squares stack data.
 *
 * @author Eric Trautman
 */
public class MLSStackClient {

    /**
     * @param  args  see {@link MLSStackClientParameters} for command line argument details.
     */
    public static void main(String[] args) {
        try {

            final MLSStackClientParameters params = MLSStackClientParameters.parseCommandLineArgs(args);

            if (params.displayHelp()) {

                params.showUsage();

            } else {

                LOG.info("main: entry, params={}", params);

                final MLSStackClient client = new MLSStackClient(params.getOwner(),
                                                                 params.getProject(),
                                                                 params.getAlignStack(),
                                                                 params.getMontageStack(),
                                                                 params.getMlsStack(),
                                                                 params.getBaseDataUrl());
                final Double alpha = params.getAlpha();
                for (String z : params.getzValues()) {
                    client.generateStackDataForZ(new Double(z), alpha);
                }

            }

        } catch (final Throwable t) {
            LOG.error("main: caught exception", t);
        }
    }

    private final String owner;
    private final String project;
    private final String alignStack;
    private final String montageStack;
    private final String mlsStack;
    private final String baseDataUrl;

    private CloseableHttpClient httpClient;

    public MLSStackClient(final String owner,
                          final String project,
                          final String alignStack,
                          final String montageStack,
                          final String mlsStack,
                          final String baseDataUrl) {
        this.owner = owner;
        this.project = project;
        this.alignStack = alignStack;
        this.montageStack = montageStack;
        this.mlsStack = mlsStack;
        this.baseDataUrl = baseDataUrl;

        this.httpClient = HttpClients.createDefault();
    }

    public void generateStackDataForZ(Double z,
                                      Double alpha)
            throws Exception {

        LOG.info("generateStackDataForZ: entry, z={}, alpha={}", z, alpha);

        final ResolvedTileSpecCollection montageTiles = getResolvedTiles(montageStack, z);
        final ResolvedTileSpecCollection alignTiles = getResolvedTiles(alignStack, z);

        final TransformSpec mlsTransformSpec = buildMovingLeastSquaresTransform(montageTiles.getTileSpecs(),
                                                                                alignTiles.getTileSpecs(),
                                                                                alpha,
                                                                                z);

        LOG.info("generateStackDataForZ: derived moving least squares transform for {}", z);

        montageTiles.addTransformSpecToCollection(mlsTransformSpec);
        montageTiles.addReferenceTransformToAllTiles(mlsTransformSpec.getId());

        LOG.info("generateStackDataForZ: added transform and derived bounding boxes for {}", z);

        saveResolvedTiles(montageTiles, z);

        LOG.info("generateStackDataForZ: exit, saved tiles and transforms for {}", z);
    }

    @Override
    public String toString() {
        return "MLSStackClient{" +
               "owner='" + owner + '\'' +
               ", project='" + project + '\'' +
               ", alignStack='" + alignStack + '\'' +
               ", montageStack='" + montageStack + '\'' +
               ", mlsStack='" + mlsStack + '\'' +
               ", baseDataUrl='" + baseDataUrl + '\'' +
               '}';
    }

    private TransformSpec buildMovingLeastSquaresTransform(Collection<TileSpec> montageTiles,
                                                           Collection<TileSpec> alignTiles,
                                                           Double alpha,
                                                           Double z)
            throws Exception {

        final MovingLeastSquaresBuilder mlsBuilder = new MovingLeastSquaresBuilder(montageTiles, alignTiles);
        final MovingLeastSquaresTransform2 transform = mlsBuilder.build(alpha);
        final String transformId = z + "_MLS";

        return new LeafTransformSpec(transformId,
                                     null,
                                     transform.getClass().getName(),
                                     transform.toDataString());
    }

    private URI getResolvedTilesUri(String stack,
                                    Double z)
            throws URISyntaxException {
        return new URI(baseDataUrl + "/owner/" + owner + "/project/" + project +
                       "/stack/" + stack + "/z/" + z + "/resolvedTiles");
    }

    private ResolvedTileSpecCollection getResolvedTiles(String stack,
                                                        Double z)
            throws URISyntaxException, IOException {

        final URI uri = getResolvedTilesUri(stack, z);
        final HttpGet httpGet = new HttpGet(uri);
        final String requestContext = "GET " + uri;
        final ResolvedTilesResponseHandler responseHandler = new ResolvedTilesResponseHandler(requestContext);

        LOG.info("getResolvedTiles: submitting {}", requestContext);

        return httpClient.execute(httpGet, responseHandler);
    }

    private void saveResolvedTiles(ResolvedTileSpecCollection resolvedTiles,
                                   Double z)
            throws URISyntaxException, IOException {
        final String json = JsonUtils.GSON.toJson(resolvedTiles);
        final StringEntity stringEntity = new StringEntity(json, ContentType.APPLICATION_JSON);
        final URI uri = getResolvedTilesUri(mlsStack, z);
        final String requestContext = "PUT " + uri;
        final ResourceCreatedResponseHandler responseHandler = new ResourceCreatedResponseHandler(requestContext);
        final HttpPut httpPut = new HttpPut(uri);
        httpPut.setEntity(stringEntity);

        LOG.info("saveResolvedTiles: submitting {}", requestContext);

        httpClient.execute(httpPut, responseHandler);
    }

    private static final Logger LOG = LoggerFactory.getLogger(MLSStackClient.class);
}

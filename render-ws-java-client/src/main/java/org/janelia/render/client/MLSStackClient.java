package org.janelia.render.client;

import mpicbg.trakem2.transform.MovingLeastSquaresTransform2;
import org.janelia.alignment.MovingLeastSquaresBuilder;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private final String alignStack;
    private final String montageStack;
    private final String mlsStack;

    private final RenderDataClient renderDataClient;

    public MLSStackClient(final String owner,
                          final String project,
                          final String alignStack,
                          final String montageStack,
                          final String mlsStack,
                          final String baseDataUrl) {

        this.alignStack = alignStack;
        this.montageStack = montageStack;
        this.mlsStack = mlsStack;

        this.renderDataClient = new RenderDataClient(baseDataUrl, owner, project);
    }

    public void generateStackDataForZ(Double z,
                                      Double alpha)
            throws Exception {

        LOG.info("generateStackDataForZ: entry, z={}, alpha={}", z, alpha);

        final ResolvedTileSpecCollection montageTiles = renderDataClient.getResolvedTiles(montageStack, z);
        final ResolvedTileSpecCollection alignTiles = renderDataClient.getResolvedTiles(alignStack, z);

        final TransformSpec mlsTransformSpec = buildMovingLeastSquaresTransform(montageTiles.getTileSpecs(),
                                                                                alignTiles.getTileSpecs(),
                                                                                alpha,
                                                                                z);

        LOG.info("generateStackDataForZ: derived moving least squares transform for {}", z);

        montageTiles.addTransformSpecToCollection(mlsTransformSpec);
        montageTiles.addReferenceTransformToAllTiles(mlsTransformSpec.getId());

        LOG.info("generateStackDataForZ: added transform and derived bounding boxes for {}", z);

        renderDataClient.saveResolvedTiles(montageTiles, mlsStack, z);

        LOG.info("generateStackDataForZ: exit, saved tiles and transforms for {}", z);
    }

    @Override
    public String toString() {
        return "MLSStackClient{" +
               "renderDataClient=" + renderDataClient +
               ", alignStack='" + alignStack + '\'' +
               ", montageStack='" + montageStack + '\'' +
               ", mlsStack='" + mlsStack + '\'' +
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

    private static final Logger LOG = LoggerFactory.getLogger(MLSStackClient.class);
}

package org.janelia.render.client;

import java.util.Collection;

import mpicbg.trakem2.transform.CoordinateTransform;

import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.warp.AbstractWarpTransformBuilder;
import org.janelia.alignment.warp.MovingLeastSquaresBuilder;
import org.janelia.alignment.warp.ThinPlateSplineBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for generating Moving Least Squares stack data.
 *
 * @author Eric Trautman
 */
public class MLSStackClient {

    /**
     * @param  args  see {@link MLSStackClientParameters} for command line argument details.
     */
    public static void main(final String[] args) {
        try {

            final MLSStackClientParameters params = MLSStackClientParameters.parseCommandLineArgs(args);

            if (params.displayHelp()) {

                params.showUsage();

            } else {

                LOG.info("main: entry, params={}", params);

                final MLSStackClient client = new MLSStackClient(params);
                final Double alpha = params.getAlpha();
                for (final String z : params.getzValues()) {
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
    private final boolean deriveTPS;

    private final RenderDataClient renderDataClient;

    public MLSStackClient(final MLSStackClientParameters params) {

        this.alignStack = params.getAlignStack();
        this.montageStack = params.getMontageStack();
        this.mlsStack = params.getMlsStack();
        this.deriveTPS = params.isDeriveTPS();

        this.renderDataClient = new RenderDataClient(params.getBaseDataUrl(), params.getOwner(), params.getProject());
    }

    public void generateStackDataForZ(final Double z,
                                      final Double alpha)
            throws Exception {

        LOG.info("generateStackDataForZ: entry, z={}, alpha={}", z, alpha);

        final ResolvedTileSpecCollection montageTiles = renderDataClient.getResolvedTiles(montageStack, z);
        final ResolvedTileSpecCollection alignTiles = renderDataClient.getResolvedTiles(alignStack, z);

        final TransformSpec mlsTransformSpec = buildTransform(montageTiles.getTileSpecs(),
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
               ", deriveTPS=" + deriveTPS +
               '}';
    }

    private TransformSpec buildTransform(final Collection<TileSpec> montageTiles,
                                         final Collection<TileSpec> alignTiles,
                                         final Double alpha,
                                         final Double z)
            throws Exception {

        final AbstractWarpTransformBuilder< ? extends CoordinateTransform > transformBuilder;
        final String transformId;
        final CoordinateTransform transform;
        
        if (deriveTPS) {        	
            
        	LOG.info("buildTransform: deriving thin plate transform");

            transformId = z + "_TPS";
            transformBuilder = new ThinPlateSplineBuilder(montageTiles, alignTiles);

            LOG.info("buildTransform: completed thin plate transform derivation");
            
        } else {
            transformId = z + "_MLS";
            transformBuilder = new MovingLeastSquaresBuilder(montageTiles, alignTiles, alpha);
        }
        
        transform = transformBuilder.call();


        return new LeafTransformSpec(transformId,
                                     null,
                                     transform.getClass().getName(),
                                     transform.toDataString());
    }

    private static final Logger LOG = LoggerFactory.getLogger(MLSStackClient.class);
}

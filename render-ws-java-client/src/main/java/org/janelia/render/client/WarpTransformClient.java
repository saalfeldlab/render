package org.janelia.render.client;

import com.beust.jcommander.Parameter;

import java.util.Collection;
import java.util.List;

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
 * Java client for generating warp transform (TPS or MLS) stack data.
 *
 * @author Eric Trautman
 */
public class WarpTransformClient {

    @SuppressWarnings("ALL")
    private static class Parameters extends RenderDataClientParameters {

        // NOTE: --baseDataUrl, --owner, and --project parameters defined in RenderDataClientParameters

        @Parameter(names = "--alignStack", description = "Align stack name", required = true)
        private String alignStack;

        @Parameter(names = "--montageStack", description = "Montage stack name", required = true)
        private String montageStack;

        @Parameter(names = "--mlsStack", description = "Target stack (tps or mls) name", required = true)
        private String mlsStack;

        @Parameter(names = "--alpha", description = "Alpha value for MLS transform", required = false)
        private Double alpha;

        @Parameter(names = "--deriveTPS", description = "Derive thin plate spline transform instead of MLS transform", required = false, arity = 0)
        private boolean deriveTPS;

        @Parameter(description = "Z values", required = true)
        private List<String> zValues;
    }

    public static void main(final String[] args) {
        try {
            final Parameters parameters = new Parameters();
            parameters.parse(args);

            LOG.info("main: entry, parameters={}", parameters);

            final WarpTransformClient client = new WarpTransformClient(parameters);
            for (final String z : parameters.zValues) {
                client.generateStackDataForZ(new Double(z), parameters.alpha);
            }

        } catch (final Throwable t) {
            LOG.error("main: caught exception", t);
        }
    }

    private final Parameters parameters;

    private final RenderDataClient renderDataClient;

    public WarpTransformClient(final Parameters parameters) {
        this.parameters = parameters;
        this.renderDataClient = parameters.getClient();
    }

    public void generateStackDataForZ(final Double z,
                                      final Double alpha)
            throws Exception {

        LOG.info("generateStackDataForZ: entry, z={}, alpha={}", z, alpha);

        final ResolvedTileSpecCollection montageTiles = renderDataClient.getResolvedTiles(parameters.montageStack, z);
        final ResolvedTileSpecCollection alignTiles = renderDataClient.getResolvedTiles(parameters.alignStack, z);

        final TransformSpec mlsTransformSpec = buildTransform(montageTiles.getTileSpecs(),
                                                              alignTiles.getTileSpecs(),
                                                              alpha,
                                                              z);

        LOG.info("generateStackDataForZ: derived moving least squares transform for {}", z);

        montageTiles.addTransformSpecToCollection(mlsTransformSpec);
        montageTiles.addReferenceTransformToAllTiles(mlsTransformSpec.getId());

        LOG.info("generateStackDataForZ: added transform and derived bounding boxes for {}", z);

        renderDataClient.saveResolvedTiles(montageTiles, parameters.mlsStack, z);

        LOG.info("generateStackDataForZ: exit, saved tiles and transforms for {}", z);
    }

    private TransformSpec buildTransform(final Collection<TileSpec> montageTiles,
                                         final Collection<TileSpec> alignTiles,
                                         final Double alpha,
                                         final Double z)
            throws Exception {

        final AbstractWarpTransformBuilder< ? extends CoordinateTransform > transformBuilder;
        final String transformId;
        final CoordinateTransform transform;
        
        if (parameters.deriveTPS) {
            
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

    private static final Logger LOG = LoggerFactory.getLogger(WarpTransformClient.class);
}

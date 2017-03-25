package org.janelia.render.client;

import com.beust.jcommander.Parameter;

import java.util.Collection;
import java.util.List;

import mpicbg.trakem2.transform.CoordinateTransform;

import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.validator.TileSpecValidator;
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
    private static class Parameters extends RenderDataClientParametersWithValidator {

        // NOTE: --baseDataUrl, --owner, and --project parameters defined in RenderDataClientParameters
        // NOTE: --validatorClass and --validatorData parameters defined in RenderDataClientParametersWithValidator

        @Parameter(names = "--alignStack", description = "Align stack name", required = true)
        private String alignStack;

        @Parameter(names = "--montageStack", description = "Montage stack name", required = true)
        private String montageStack;

        @Parameter(names = "--targetStack", description = "Target stack (tps or mls) name", required = true)
        private String targetStack;

        @Parameter(names = "--alpha", description = "Alpha value for MLS transform", required = false)
        private Double alpha;

        @Parameter(names = "--deriveMLS", description = "Derive moving least squares transforms instead of thin plate spline transforms", required = false, arity = 0)
        private boolean deriveMLS;

        @Parameter(description = "Z values", required = true)
        private List<String> zValues;
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args, WarpTransformClient.class);

                LOG.info("runClient: entry, parameters={}", parameters);

                final WarpTransformClient client = new WarpTransformClient(parameters);

                client.setUpDerivedStack();

                for (final String z : parameters.zValues) {
                    client.generateStackDataForZ(new Double(z), parameters.alpha);
                }
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final TileSpecValidator tileSpecValidator;

    private final RenderDataClient renderDataClient;

    public WarpTransformClient(final Parameters parameters) {
        this.parameters = parameters;
        this.tileSpecValidator = parameters.getValidatorInstance();

        this.renderDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                     parameters.owner,
                                                     parameters.project);
    }

    public void setUpDerivedStack() throws Exception {
        final StackMetaData montageStackMetaData = renderDataClient.getStackMetaData(parameters.montageStack);
        renderDataClient.setupDerivedStack(montageStackMetaData, parameters.targetStack);
    }

    public void generateStackDataForZ(final Double z,
                                      final Double alpha)
            throws Exception {

        LOG.info("generateStackDataForZ: entry, z={}, alpha={}", z, alpha);

        final ResolvedTileSpecCollection montageTiles = renderDataClient.getResolvedTiles(parameters.montageStack, z);
        final ResolvedTileSpecCollection alignTiles = renderDataClient.getResolvedTiles(parameters.alignStack, z);

        final TransformSpec warpTransformSpec = buildTransform(montageTiles.getTileSpecs(),
                                                               alignTiles.getTileSpecs(),
                                                               alpha,
                                                               z);

        LOG.info("generateStackDataForZ: derived warp transform for {}", z);

        montageTiles.addTransformSpecToCollection(warpTransformSpec);
        montageTiles.addReferenceTransformToAllTiles(warpTransformSpec.getId(), false);

        final int totalNumberOfTiles = montageTiles.getTileCount();
        if (tileSpecValidator != null) {
            montageTiles.setTileSpecValidator(tileSpecValidator);
            montageTiles.filterInvalidSpecs();
        }
        final int numberOfRemovedTiles = totalNumberOfTiles - montageTiles.getTileCount();

        LOG.info("generateStackDataForZ: added transform and derived bounding boxes for {} tiles with z of {}, removed {} bad tiles",
                 totalNumberOfTiles, z, numberOfRemovedTiles);

        if (montageTiles.getTileCount() == 0) {
            throw new IllegalStateException("no tiles left to save after filtering invalid tiles");
        }

        renderDataClient.saveResolvedTiles(montageTiles, parameters.targetStack, z);

        LOG.info("generateStackDataForZ: exit, saved tiles and transforms for {}", z);
    }

    private TransformSpec buildTransform(final Collection<TileSpec> montageTiles,
                                         final Collection<TileSpec> alignTiles,
                                         final Double alpha,
                                         final Double z)
            throws Exception {

        final String warpType = parameters.deriveMLS ? "MLS" : "TPS";

        LOG.info("buildTransform: deriving {} transform", warpType);

        final AbstractWarpTransformBuilder< ? extends CoordinateTransform > transformBuilder;
        final String transformId = z + "_" + warpType;
        final CoordinateTransform transform;

        if (parameters.deriveMLS) {
            transformBuilder = new MovingLeastSquaresBuilder(montageTiles, alignTiles, alpha);
        } else {
            transformBuilder = new ThinPlateSplineBuilder(montageTiles, alignTiles);
        }
        
        transform = transformBuilder.call();

        LOG.info("buildTransform: completed {} transform derivation", warpType);

        return new LeafTransformSpec(transformId,
                                     null,
                                     transform.getClass().getName(),
                                     transform.toDataString());
    }

    private static final Logger LOG = LoggerFactory.getLogger(WarpTransformClient.class);
}

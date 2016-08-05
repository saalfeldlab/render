package org.janelia.render.client;

import com.beust.jcommander.Parameter;

import java.util.List;

import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ListTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.validator.TileSpecValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for fixing auto loader scale values embedded in shared lens correction transform list.
 *
 * @author Eric Trautman
 */
public class FixAutoLoaderScaleClient {

    @SuppressWarnings("ALL")
    private static class Parameters extends RenderDataClientParameters {

        // NOTE: --baseDataUrl, --owner, and --project parameters defined in RenderDataClientParameters

        @Parameter(
                names = "--stack",
                description = "Name of source stack containing base tile specifications",
                required = true)
        private String stack;

        @Parameter(
                names = "--targetProject",
                description = "Name of target project that will contain imported transforms (default is to reuse source project)",
                required = false)
        private String targetProject;

        @Parameter(
                names = "--targetStack",
                description = "Name of target (align, montage, etc.) stack that will contain imported transforms",
                required = true)
        private String targetStack;

        @Parameter(
                names = "--newScale",
                description = "Corrected scale",
                required = true)
        private Double newScale;

        @Parameter(
                names = "--oldScale",
                description = "Current scale to be replaced",
                required = false)
        private Double oldScale = 0.935;

        @Parameter(description = "Z values of layers with tiles needing correction", required = true)
        private List<Double> zValues;
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args, FixAutoLoaderScaleClient.class);

                LOG.info("runClient: entry, parameters={}", parameters);

                final FixAutoLoaderScaleClient client = new FixAutoLoaderScaleClient(parameters);

                for (final Double z : parameters.zValues) {
                    client.updateTileDataForSection(z);
                }
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final TileSpecValidator tileSpecValidator;

    private final RenderDataClient sourceRenderDataClient;
    private final RenderDataClient targetRenderDataClient;

    private final String newData;
    private final String oldData;

    public FixAutoLoaderScaleClient(final Parameters parameters) {

        this.parameters = parameters;
        this.tileSpecValidator = parameters.getValidatorInstance();

        this.sourceRenderDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                           parameters.owner,
                                                           parameters.project);

        if ((parameters.targetProject == null) ||
            (parameters.targetProject.trim().length() == 0) ||
            (parameters.targetProject.equals(parameters.project))){
            this.targetRenderDataClient = sourceRenderDataClient;
        } else {
            this.targetRenderDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                               parameters.owner,
                                                               parameters.targetProject);
        }

        this.newData = parameters.newScale + " 0.0 0.0 " + parameters.newScale + " 0 0";
        this.oldData = parameters.oldScale + " 0.0 0.0 " + parameters.oldScale + " 0 0";
    }

    public void updateTileDataForSection(final Double z) throws Exception {

        LOG.info("updateTilesForSection: entry, z={}", z);

        final ResolvedTileSpecCollection tileData = sourceRenderDataClient.getResolvedTiles(parameters.stack, z);

        tileData.setTileSpecValidator(tileSpecValidator);

        int fixCount = 0;

        for (final TransformSpec transformSpec : tileData.getTransformSpecs()) {
            if (transformSpec instanceof ListTransformSpec) {
                final ListTransformSpec lensCorrection = (ListTransformSpec) transformSpec;
                final TransformSpec lastSpec = lensCorrection.getLastSpec();
                if (lastSpec instanceof LeafTransformSpec) {
                    final LeafTransformSpec oldScaleSpec = (LeafTransformSpec) lastSpec;
                    if (oldData.equals(oldScaleSpec.getDataString())) {
                        final LeafTransformSpec newScaleSpec = new LeafTransformSpec(oldScaleSpec.getId(),
                                                                                     null,
                                                                                     oldScaleSpec.getClassName(),
                                                                                     newData);
                        lensCorrection.removeLastSpec();
                        lensCorrection.addSpec(newScaleSpec);
                        fixCount++;
                        LOG.info("within {} replaced {} with {}",
                                 lensCorrection.getId(), oldScaleSpec.toJson(), newScaleSpec.toJson());
                    }
                }
            }
        }

        LOG.info("updateTilesForSection: fixed {} shared transforms", fixCount);

        if (fixCount > 0) {

            tileData.recalculateBoundingBoxes();
            tileData.removeUnreferencedTransforms();

            final int tileSpecCount = tileData.getTileCount();

            tileData.filterInvalidSpecs();

            final int removedTiles = tileSpecCount - tileData.getTileCount();

            targetRenderDataClient.saveResolvedTiles(tileData, parameters.targetStack, z);

            LOG.info("updateTilesForSection: updated {} tiles for z {}, removed {} bad tiles",
                     tileSpecCount, z, removedTiles);
        } else {

            LOG.info("updateTilesForSection: nothing to fix for z {}, simply cloning stack", z);

            targetRenderDataClient.saveResolvedTiles(tileData, parameters.targetStack, z);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(FixAutoLoaderScaleClient.class);
}

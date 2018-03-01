package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.util.List;

import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ListTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.validator.TileSpecValidator;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.TileSpecValidatorParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for fixing auto loader scale values embedded in shared lens correction transform list.
 *
 * @author Eric Trautman
 */
public class FixAutoLoaderScaleClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @ParametersDelegate
        public TileSpecValidatorParameters tileSpecValidator = new TileSpecValidatorParameters();

        @Parameter(
                names = "--stack",
                description = "Name of source stack containing base tile specifications",
                required = true)
        public String stack;

        @Parameter(
                names = "--targetProject",
                description = "Name of target project that will contain imported transforms (default is to reuse source project)",
                required = false)
        public String targetProject;

        @Parameter(
                names = "--targetStack",
                description = "Name of target (align, montage, etc.) stack that will contain imported transforms",
                required = true)
        public String targetStack;

        @Parameter(
                names = "--newScale",
                description = "Corrected scale",
                required = true)
        public Double newScale;

        @Parameter(
                names = "--oldScale",
                description = "Current scale to be replaced",
                required = false)
        public Double oldScale = 0.935;

        @Parameter(description = "Z values of layers with tiles needing correction", required = true)
        public List<Double> zValues;
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

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
        this.tileSpecValidator = parameters.tileSpecValidator.getValidatorInstance();

        this.sourceRenderDataClient = parameters.renderWeb.getDataClient();

        if ((parameters.targetProject == null) ||
            (parameters.targetProject.trim().length() == 0) ||
            (parameters.targetProject.equals(parameters.renderWeb.project))){
            this.targetRenderDataClient = sourceRenderDataClient;
        } else {
            this.targetRenderDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                               parameters.renderWeb.owner,
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

            tileData.removeInvalidTileSpecs();

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

package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.util.List;

import org.janelia.alignment.spec.ListTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.validator.TileSpecValidator;
import org.janelia.alignment.util.ProcessTimer;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.TileSpecValidatorParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for removing a specified number of transforms from each tile spec in a layer.
 *
 * @author Eric Trautman
 */
public class TransformRemovalClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @ParametersDelegate
        public TileSpecValidatorParameters tileSpecValidator = new TileSpecValidatorParameters();

        @Parameter(
                names = "--stack",
                description = "Name of source stack",
                required = true)
        public String stack;

        @Parameter(
                names = "--targetOwner",
                description = "Name of owner for target stack (default is to reuse source owner)")
        public String targetOwner;

        @Parameter(
                names = "--targetProject",
                description = "Name of project for target stack (default is to reuse source project)")
        public String targetProject;

        @Parameter(
                names = "--targetStack",
                description = "Name of target stack",
                required = true)
        public String targetStack;

        @Parameter(
                names = "--numberOfTransformsToRemove",
                description = "Number of transforms to remove from each tile",
                required = true)
        public Integer numberOfTransformsToRemove = 1;

        @Parameter(
                names = "--completeTargetStack",
                description = "Complete the target stack after removing transforms for all layers",
                arity = 0)
        public boolean completeTargetStack = false;

        @Parameter(
                names = "--z",
                description = "Z value of section to be processed",
                required = true)
        public List<Double> zValues;

        String getTargetOwner() {
            if (targetOwner == null) {
                targetOwner = renderWeb.owner;
            }
            return targetOwner;
        }

        public String getTargetProject() {
            if (targetProject == null) {
                targetProject = renderWeb.project;
            }
            return targetProject;
        }

    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final TransformRemovalClient client = new TransformRemovalClient(parameters);

                client.setUpDerivedStack();

                for (final Double z : parameters.zValues) {
                    client.updateLayer(z);
                }

                if (parameters.completeTargetStack) {
                    client.completeTargetStack();
                }
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final TileSpecValidator tileSpecValidator;

    private final RenderDataClient sourceRenderDataClient;
    private final RenderDataClient targetRenderDataClient;

    private TransformRemovalClient(final Parameters parameters) {

        this.parameters = parameters;
        this.tileSpecValidator = parameters.tileSpecValidator.getValidatorInstance();

        this.sourceRenderDataClient = parameters.renderWeb.getDataClient();

        this.targetRenderDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                           parameters.getTargetOwner(),
                                                           parameters.getTargetProject());
    }

    private void setUpDerivedStack() throws Exception {
        final StackMetaData fromStackMetaData = sourceRenderDataClient.getStackMetaData(parameters.stack);
        targetRenderDataClient.setupDerivedStack(fromStackMetaData, parameters.targetStack);
    }

    private void updateLayer(final Double z) throws Exception {

        final ResolvedTileSpecCollection sourceCollection =
                sourceRenderDataClient.getResolvedTiles(parameters.stack, z);


        final ProcessTimer timer = new ProcessTimer();
        int tileSpecCount = 0;
        for (final TileSpec tileSpec : sourceCollection.getTileSpecs()) {

            tileSpecCount++;

            final ListTransformSpec transforms = tileSpec.getTransforms();

            for (int i = 0; i < parameters.numberOfTransformsToRemove; i++) {
                transforms.removeLastSpec();
            }

            if (! transforms.isFullyResolved()) {
                transforms.resolveReferences(sourceCollection.getTransformIdToSpecMap());
                if (! transforms.isFullyResolved()) {
                    throw new IllegalArgumentException("tile " + tileSpec.getTileId() +
                                                       " requires the following transform ids " +
                                                       transforms.getUnresolvedIds());
                }
            }

            tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true);

            if (timer.hasIntervalPassed()) {
                LOG.info("updateLayer: removed transforms for {} out of {} tiles with z {}",
                         tileSpecCount, sourceCollection.getTileCount(), z);
            }
        }

        sourceCollection.removeUnreferencedTransforms();

        sourceCollection.setTileSpecValidator(tileSpecValidator);
        sourceCollection.removeInvalidTileSpecs();

        targetRenderDataClient.saveResolvedTiles(sourceCollection, parameters.targetStack, z);
    }

    private void completeTargetStack() throws Exception {
        targetRenderDataClient.setStackState(parameters.targetStack, StackMetaData.StackState.COMPLETE);
    }

    private static final Logger LOG = LoggerFactory.getLogger(TransformRemovalClient.class);
}

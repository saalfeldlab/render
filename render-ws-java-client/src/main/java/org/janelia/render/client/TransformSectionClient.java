package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.util.List;

import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.validator.TileSpecValidator;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.TileSpecValidatorParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for adding a transform to all tiles in one or more sections of a stack.
 *
 * @author Eric Trautman
 */
public class TransformSectionClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @ParametersDelegate
        public TileSpecValidatorParameters tileSpecValidator = new TileSpecValidatorParameters();

        @Parameter(
                names = "--stack",
                description = "Stack name",
                required = true)
        public String stack;

        @Parameter(
                names = "--targetProject",
                description = "Name of target project that will contain transformed tiles (default is to reuse source project)",
                required = false)
        private String targetProject;

        @Parameter(
                names = "--targetStack",
                description = "Name of target stack that will contain transformed tiles (default is to reuse source stack)",
                required = false)
        private String targetStack;

        @Parameter(
                names = "--transformId",
                description = "Identifier for transformation",
                required = true)
        public String transformId;

        @Parameter(
                names = "--transformClass",
                description = "Name of transformation implementation (java) class",
                required = true)
        public String transformClass;

        // TODO: figure out less hacky way to handle spaces in transform data string
        @Parameter(
                names = "--transformData",
                description = "Data with which transformation implementation should be initialized (expects values to be separated by ',' instead of ' ')",
                required = true)
        public String transformData;

        @Parameter(
                names = "--replaceLast",
                description = "Replace each tile's last transform with this one (default is to append new transform)",
                required = false,
                arity = 0)
        public boolean replaceLast;

        @Parameter(
                description = "Z values",
                required = true)
        public List<String> zValues;

        public String getTargetStack() {
            if ((targetStack == null) || (targetStack.trim().length() == 0)) {
                targetStack = stack;
            }
            return targetStack;
        }
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final TransformSectionClient client = new TransformSectionClient(parameters);

                client.setupDerivedStack();

                for (final String z : parameters.zValues) {
                    client.generateStackDataForZ(new Double(z));
                }
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final TileSpecValidator tileSpecValidator;
    private final LeafTransformSpec stackTransform;

    private final RenderDataClient sourceRenderDataClient;
    private final RenderDataClient targetRenderDataClient;

    public TransformSectionClient(final Parameters parameters) {

        this.parameters = parameters;

        this.stackTransform = new LeafTransformSpec(parameters.transformId,
                                                    null,
                                                    parameters.transformClass,
                                                    parameters.transformData.replace(',', ' '));

        this.tileSpecValidator = parameters.tileSpecValidator.getValidatorInstance();

        this.sourceRenderDataClient = parameters.renderWeb.getDataClient();

        if ((parameters.targetProject == null) ||
            (parameters.targetProject.trim().length() == 0) ||
            (parameters.targetProject.equals(parameters.renderWeb.project))) {
            this.targetRenderDataClient = sourceRenderDataClient;
        } else {
            this.targetRenderDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                               parameters.renderWeb.owner,
                                                               parameters.targetProject);
        }

    }

    public void setupDerivedStack()
            throws IOException {
        final StackMetaData sourceStackMetaData = sourceRenderDataClient.getStackMetaData(parameters.stack);
        targetRenderDataClient.setupDerivedStack(sourceStackMetaData, parameters.getTargetStack());
    }

    public void generateStackDataForZ(final Double z)
            throws Exception {

        LOG.info("generateStackDataForZ: entry, z={}", z);

        final ResolvedTileSpecCollection tiles = sourceRenderDataClient.getResolvedTiles(parameters.stack, z);

        tiles.addTransformSpecToCollection(stackTransform);
        tiles.addReferenceTransformToAllTiles(stackTransform.getId(), parameters.replaceLast);

        final int totalNumberOfTiles = tiles.getTileCount();
        if (tileSpecValidator != null) {
            tiles.setTileSpecValidator(tileSpecValidator);
            tiles.removeInvalidTileSpecs();
        }
        final int numberOfRemovedTiles = totalNumberOfTiles - tiles.getTileCount();

        LOG.info("generateStackDataForZ: added transform and derived bounding boxes for {} tiles with z of {}, removed {} bad tiles",
                 totalNumberOfTiles, z, numberOfRemovedTiles);

        targetRenderDataClient.saveResolvedTiles(tiles, parameters.getTargetStack(), z);

        LOG.info("generateStackDataForZ: exit, saved tiles and transforms for {}", z);
    }

    private static final Logger LOG = LoggerFactory.getLogger(TransformSectionClient.class);
}

package org.janelia.render.client.spark.tile;

import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.janelia.alignment.multisem.MultiSemUtilities;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackIdNamingGroup;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineParameters;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStep;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStepId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for updating the tileId for all tiles in the stack
 * to use the new wafer 60 and 61 render order.
 */
public class TileIdHackClient
        implements Serializable, AlignmentPipelineStep {

    public static class Parameters extends CommandLineParameters {
        @ParametersDelegate
        public MultiProjectParameters multiProject;

        public Parameters() {
            this(new MultiProjectParameters());
        }

        public Parameters(final MultiProjectParameters multiProject) {
            this.multiProject = multiProject;
        }
    }

    /** Run the client with command line parameters. */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {
                final Parameters parameters = new Parameters();
                parameters.parse(args);
                final TileIdHackClient client = new TileIdHackClient();
                client.createContextAndRun(parameters);
            }
        };
        clientRunner.run();
    }

    /** Empty constructor required for alignment pipeline steps. */
    public TileIdHackClient() {
    }

    /** Create a spark context and run the client with the specified parameters. */
    public void createContextAndRun(final Parameters clientParameters) throws IOException {
        final SparkConf conf = new SparkConf().setAppName(getClass().getSimpleName());
        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            LOG.info("createContextAndRun: appId is {}", sparkContext.getConf().getAppId());
            copyStackAndFixTileIds(sparkContext, clientParameters);
        }
    }

    /** Validates the specified pipeline parameters are sufficient. */
    @Override
    public void validatePipelineParameters(final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException {
        // nothing to validate
    }

    /** Run the client as part of an alignment pipeline. */
    public void runPipelineStep(final JavaSparkContext sparkContext,
                                final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException, IOException {

        final StackIdNamingGroup otherNamingGroup = pipelineParameters.getOtherNamingGroup();
        if (otherNamingGroup == null) {
            throw new IllegalArgumentException(
                    "The " + AlignmentPipelineStepId.HACK_TILE_ID + " pipeline step requires that " +
                    "an 'other' pipelineStackGroup is defined in the pipeline parameters.");
        }
        final MultiProjectParameters multiProject = pipelineParameters.getMultiProject(otherNamingGroup);
        final Parameters clientParameters = new Parameters(multiProject);
        copyStackAndFixTileIds(sparkContext, clientParameters);
    }

    @Override
    public AlignmentPipelineStepId getDefaultStepId() {
        return AlignmentPipelineStepId.HACK_TILE_ID;
    }

    private void copyStackAndFixTileIds(final JavaSparkContext sparkContext,
                                        final Parameters clientParameters)
            throws IOException {

        LOG.info("copyStackAndFixTileIds: entry, clientParameters={}", clientParameters);

        final MultiProjectParameters multiProjectParameters = clientParameters.multiProject;
        final String baseDataUrl = multiProjectParameters.getBaseDataUrl();
        final List<StackWithZValues> stackWithZValuesList = multiProjectParameters.buildListOfStackWithAllZ();

        final JavaRDD<StackWithZValues> rddStackWithZValues = sparkContext.parallelize(stackWithZValuesList);

        final Function<StackWithZValues, Void> copyAndFixTileIdFunction = stackWithZ -> {

            LogUtilities.setupExecutorLog4j(stackWithZ.toString());

            final StackId stackId = stackWithZ.getStackId();
            final String stack = stackId.getStack();
            final RenderDataClient sourceDataClient = new RenderDataClient(baseDataUrl,
                                                                           stackId.getOwner(),
                                                                           stackId.getProject());

            sourceDataClient.setStackState(stack, StackMetaData.StackState.LOADING);

            for (final Double z : stackWithZ.getzValues()) {
                final ResolvedTileSpecCollection resolvedTiles = sourceDataClient.getResolvedTiles(stack, z);

                // tile specs are keyed by tileId within a collection, so build a new collection after fixing the ids
                final List<TileSpec> tileSpecsWithFixedIds = new ArrayList<>(resolvedTiles.getTileSpecs());
                tileSpecsWithFixedIds.forEach(tileSpec -> {
                    final String fixedTileId =
                            MultiSemUtilities.convertTileIdToUseWafers6061RenderOrder(tileSpec.getTileId());
                    tileSpec.setTileId(fixedTileId);
                });

                final ResolvedTileSpecCollection resolvedTilesWithFixedIds =
                        new ResolvedTileSpecCollection(resolvedTiles.getTransformSpecs(),
                                                       tileSpecsWithFixedIds);

                sourceDataClient.deleteStack(stack, z); // remove original tile specs for z layer
                sourceDataClient.saveResolvedTiles(resolvedTilesWithFixedIds, stack, z); // save new tile specs for z layer
            }

            sourceDataClient.setStackState(stack, StackMetaData.StackState.COMPLETE);

            return null;
        };

        final JavaRDD<Void> rddCopyAndFixTileId = rddStackWithZValues.map(copyAndFixTileIdFunction);
        rddCopyAndFixTileId.collect();

        LOG.info("copyStackAndFixTileIds: collected rddCopyAndFixTileId");
        LOG.info("copyStackAndFixTileIds: exit");
    }

    private static final Logger LOG = LoggerFactory.getLogger(TileIdHackClient.class);
}

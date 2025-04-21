package org.janelia.render.client.spark.multisem;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.janelia.alignment.spec.stack.StackId;
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

import static org.janelia.render.client.multisem.UnconnectedMontageMFOVClient.findIsolatedMFOVsInStack;

/**
 * Spark client for finding adjacent MFOVs in the same z layer that are not connected.
 * Core logic is implemented in {@link org.janelia.render.client.multisem.UnconnectedMontageMFOVClient}.
 */
public class UnconnectedMontageMFOVClient
        implements Serializable, AlignmentPipelineStep {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public MultiProjectParameters multiProject = new MultiProjectParameters();

        @Parameter(
                names = "--startPositionMatchWeight",
                description = "Weight (e.g. 0.001) for matches derived from SFOV start positions.  " +
                              "Specify to patch all isolated edge pairs with positions based upon SFOV stage locations.  " +
                              "Omit to skip start position derivation.")
        public Double startPositionMatchWeight;
    }

    /** Run the client with command line parameters. */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {
                final Parameters parameters = new Parameters();
                parameters.parse(args);
                final UnconnectedMontageMFOVClient client = new UnconnectedMontageMFOVClient();
                client.createContextAndRun(parameters);
            }
        };
        clientRunner.run();
    }

    /** Empty constructor required for alignment pipeline steps. */
    public UnconnectedMontageMFOVClient() {
    }

    /** Create a spark context and run the client with the specified parameters. */
    public void createContextAndRun(final Parameters clientParameters) throws IOException {
        final SparkConf conf = new SparkConf().setAppName(getClass().getSimpleName());
        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            LOG.info("createContextAndRun: appId is {}", sparkContext.getConf().getAppId());
            labelIsolatedEdgeMFOVs(sparkContext,
                                   clientParameters.multiProject,
                                   clientParameters.startPositionMatchWeight);
        }
    }

    @Override
    public void validatePipelineParameters(final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException {
        // nothing to validate
    }

    /** Run the client as part of an alignment pipeline. */
    public void runPipelineStep(final JavaSparkContext sparkContext,
                                final AlignmentPipelineParameters pipelineParameters)
            throws IOException {
        labelIsolatedEdgeMFOVs(sparkContext,
                               pipelineParameters.getMultiProject(pipelineParameters.getRawNamingGroup()),
                               pipelineParameters.getUnconnectedMfovEdgeStartPositionMatchWeight());
    }


    @Override
    public AlignmentPipelineStepId getDefaultStepId() {
        return AlignmentPipelineStepId.PATCH_MFOV_MONTAGE_MATCHES_USING_SAME_LAYER_DATA;
    }

    private void labelIsolatedEdgeMFOVs(final JavaSparkContext sparkContext,
                                        final MultiProjectParameters multiProjectParameters,
                                        final Double startPositionMatchWeight)
            throws IOException {

        LOG.info("labelIsolatedEdgeMFOVs: entry, multiProjectParameters={}",
                 multiProjectParameters.toJson());

        final String baseDataUrl = multiProjectParameters.getBaseDataUrl();

        final List<StackWithZValues> stackWithZValuesList = multiProjectParameters.buildListOfStackWithAllZ();

        final JavaRDD<StackWithZValues> rddStackWithZValues = sparkContext.parallelize(stackWithZValuesList);

        final Function<StackWithZValues, Void> findFunction = stackWithZValues -> {

            LogUtilities.setupExecutorLog4j(stackWithZValues.getStackId().toDevString());

            final StackId stackId = stackWithZValues.getStackId();
            final RenderDataClient renderDataClient =
                    new RenderDataClient(baseDataUrl, stackId.getOwner(), stackId.getProject());

            findIsolatedMFOVsInStack(stackWithZValues,
                                     false,
                                     renderDataClient,
                                     true, // add label to all tiles in MFOVs with isolated edges
                                     startPositionMatchWeight);
            return null;
        };

        rddStackWithZValues.map(findFunction).collect();

        LOG.info("labelIsolatedEdgeMFOVs: exit");
    }

    private static final Logger LOG = LoggerFactory.getLogger(UnconnectedMontageMFOVClient.class);
}

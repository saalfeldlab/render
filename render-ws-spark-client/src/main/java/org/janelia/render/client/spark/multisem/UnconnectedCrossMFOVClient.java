package org.janelia.render.client.spark.multisem;

import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.janelia.alignment.multisem.UnconnectedMFOVPairsForStack;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MultiStackParameters;
import org.janelia.render.client.parameter.UnconnectedCrossMFOVParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineParameters;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for patching matches missing from adjacent SFOV tile pairs within the same MFOV and z layer.
 * Core logic is implemented in {@link org.janelia.render.client.multisem.MFOVMontageMatchPatchClient}.
 *
 * @author Eric Trautman
 */
public class UnconnectedCrossMFOVClient
        implements Serializable, AlignmentPipelineStep {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public MultiStackParameters multiStack = new MultiStackParameters();

        @ParametersDelegate
        public UnconnectedCrossMFOVParameters core = new UnconnectedCrossMFOVParameters();

        public org.janelia.render.client.multisem.UnconnectedCrossMFOVClient buildJavaClient() {
            final org.janelia.render.client.multisem.UnconnectedCrossMFOVClient.Parameters javaClientParameters =
                    new org.janelia.render.client.multisem.UnconnectedCrossMFOVClient.Parameters();
            javaClientParameters.multiStack = multiStack;
            javaClientParameters.core = core;
            return new org.janelia.render.client.multisem.UnconnectedCrossMFOVClient(javaClientParameters);
        }
    }

    /** Run the client with command line parameters. */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {
                final Parameters parameters = new Parameters();
                parameters.parse(args);
                final UnconnectedCrossMFOVClient client = new UnconnectedCrossMFOVClient();
                client.createContextAndRun(parameters);
            }
        };
        clientRunner.run();
    }

    /** Empty constructor required for alignment pipeline steps. */
    public UnconnectedCrossMFOVClient() throws IllegalArgumentException {
    }

    /** Create a spark context and run the client with the specified parameters. */
    public void createContextAndRun(final Parameters clientParameters) throws IOException {
        final SparkConf conf = new SparkConf().setAppName(getClass().getSimpleName());
        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            LOG.info("createContextAndRun: appId is {}", sparkContext.getConf().getAppId());
            final List<UnconnectedMFOVPairsForStack> unconnectedMFOVsForAllStacks =
                    findUnconnectedMFOVs(sparkContext, clientParameters);

            final org.janelia.render.client.multisem.UnconnectedCrossMFOVClient jClient = clientParameters.buildJavaClient();
            jClient.logOrStoreUnconnectedMFOVPairs(unconnectedMFOVsForAllStacks);
        }
    }

    /** Validates the specified pipeline parameters are sufficient. */
    @Override
    public void validatePipelineParameters(final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException {
        AlignmentPipelineParameters.validateRequiredElementExists("unconnectedCrossMfov",
                                                                  pipelineParameters.getUnconnectedCrossMfov());
    }

    /** Run the client as part of an alignment pipeline. */
    public void runPipelineStep(final JavaSparkContext sparkContext,
                                final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException, IOException {

        final Parameters clientParameters = new Parameters();
        clientParameters.multiStack = pipelineParameters.getMultiStack(pipelineParameters.getRawNamingGroup());
        clientParameters.core = pipelineParameters.getUnconnectedCrossMfov();
        final List<UnconnectedMFOVPairsForStack> unconnectedMFOVsForAllStacks =
                findUnconnectedMFOVs(sparkContext, clientParameters);

        if (! unconnectedMFOVsForAllStacks.isEmpty()) {
            final String errorMessage =
                    "found " + unconnectedMFOVsForAllStacks.size() + " stacks with unconnected MFOVs";
            LOG.error("runPipelineStep: {}: {}", errorMessage, unconnectedMFOVsForAllStacks);
            throw new IOException(errorMessage);
        } else {
            LOG.info("runPipelineStep: all MFOVs in all stacks are connected");
        }
    }

    private List<UnconnectedMFOVPairsForStack> findUnconnectedMFOVs(final JavaSparkContext sparkContext,
                                                                    final Parameters clientParameters)
            throws IOException {

        LOG.info("findUnconnectedMFOVs: entry");

        final MultiStackParameters multiStackParameters = clientParameters.multiStack;
        final String baseDataUrl = multiStackParameters.getBaseDataUrl();
        final List<StackWithZValues> stackWithZValuesList = multiStackParameters.buildListOfStackWithAllZ();

        LOG.info("findUnconnectedMFOVs: distributing tasks for {} stacks", stackWithZValuesList.size());

        final JavaRDD<StackWithZValues> rddStackWithZValues = sparkContext.parallelize(stackWithZValuesList);

        final Function<StackWithZValues, UnconnectedMFOVPairsForStack> findFunction = stackWithZ -> {

            LogUtilities.setupExecutorLog4j(stackWithZ.toString());

            final StackId stackId = stackWithZ.getStackId();
            final RenderDataClient localDataClient = new RenderDataClient(baseDataUrl,
                                                                          stackId.getOwner(),
                                                                          stackId.getProject());
            final org.janelia.render.client.multisem.UnconnectedCrossMFOVClient jClient = clientParameters.buildJavaClient();

            return jClient.findUnconnectedMFOVs(stackWithZ,
                                                multiStackParameters.deriveMatchCollectionNamesFromProject,
                                                localDataClient);
        };

        final JavaRDD<UnconnectedMFOVPairsForStack> rddUnconnected = rddStackWithZValues.map(findFunction);
        final List<UnconnectedMFOVPairsForStack> possiblyEmptyUnconnectedList = rddUnconnected.collect();

        LOG.info("findUnconnectedMFOVs: collected {} items from rddUnconnected", possiblyEmptyUnconnectedList.size());

        final List<UnconnectedMFOVPairsForStack> unconnectedMFOVsForAllStacks = new ArrayList<>();
        for (final UnconnectedMFOVPairsForStack unconnectedMFOVPairsForStack : possiblyEmptyUnconnectedList) {
            if (unconnectedMFOVPairsForStack.size() > 0) {
                unconnectedMFOVsForAllStacks.add(unconnectedMFOVPairsForStack);
            }
        }

        LOG.info("findUnconnectedMFOVs: exit, returning {} items", unconnectedMFOVsForAllStacks.size());

        return unconnectedMFOVsForAllStacks;
    }

    private static final Logger LOG = LoggerFactory.getLogger(UnconnectedCrossMFOVClient.class);
}

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
import org.janelia.render.client.parameter.AlignmentPipelineParameters;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.parameter.UnconnectedCrossMFOVParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for patching matches missing from adjacent SFOV tile pairs within the same MFOV and z layer.
 * Core logic is implemented in {@link org.janelia.render.client.multisem.MFOVMontageMatchPatchClient}.
 *
 * @author Eric Trautman
 */
public class UnconnectedCrossMFOVClient
        implements Serializable {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public MultiProjectParameters multiProject = new MultiProjectParameters();

        @ParametersDelegate
        public UnconnectedCrossMFOVParameters core = new UnconnectedCrossMFOVParameters();

        public org.janelia.render.client.multisem.UnconnectedCrossMFOVClient buildJavaClient() {
            final org.janelia.render.client.multisem.UnconnectedCrossMFOVClient.Parameters javaClientParameters =
                    new org.janelia.render.client.multisem.UnconnectedCrossMFOVClient.Parameters();
            javaClientParameters.multiProject = multiProject;
            javaClientParameters.core = core;
            return new org.janelia.render.client.multisem.UnconnectedCrossMFOVClient(javaClientParameters);
        }

        /** @return client specific parameters populated from specified alignment pipeline parameters. */
        public static Parameters fromPipeline(final AlignmentPipelineParameters alignmentPipelineParameters) {
            final Parameters derivedParameters = new Parameters();
            derivedParameters.multiProject = alignmentPipelineParameters.getMultiProject();
            derivedParameters.core = alignmentPipelineParameters.getUnconnectedCrossMfov();
            return derivedParameters;
        }

    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                final UnconnectedCrossMFOVClient client = new UnconnectedCrossMFOVClient(parameters);
                client.run();
            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;

    public UnconnectedCrossMFOVClient(final Parameters parameters) throws IllegalArgumentException {
        LOG.info("init: parameters={}", parameters);
        this.parameters = parameters;
    }

    public void run() throws IOException {

        final SparkConf conf = new SparkConf().setAppName("UnconnectedCrossMFOVClient");

        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            final String sparkAppId = sparkContext.getConf().getAppId();
            LOG.info("run: appId is {}", sparkAppId);

            final List<UnconnectedMFOVPairsForStack> unconnectedMFOVsForAllStacks = findUnconnectedMFOVs(sparkContext);

            final org.janelia.render.client.multisem.UnconnectedCrossMFOVClient jClient = parameters.buildJavaClient();
            jClient.logOrStoreUnconnectedMFOVPairs(unconnectedMFOVsForAllStacks);
        }
    }

    public List<UnconnectedMFOVPairsForStack> findUnconnectedMFOVs(final JavaSparkContext sparkContext)
            throws IOException {

        LOG.info("findUnconnectedMFOVs: entry");

        final MultiProjectParameters multiProject = parameters.multiProject;
        final RenderDataClient sourceDataClient = multiProject.getDataClient();
        final String baseDataUrl = sourceDataClient.getBaseDataUrl();

        // override --zValuesPerBatch with Integer.MAX_VALUE because all z layers in each stack are needed for patching
        final List<StackWithZValues> stackWithZValuesList =
                multiProject.stackIdWithZ.getStackWithZList(sourceDataClient,
                                                            Integer.MAX_VALUE);
        if (stackWithZValuesList.size() == 0) {
            throw new IllegalArgumentException("no stack z-layers match parameters");
        }

        LOG.info("findUnconnectedMFOVs: distributing tasks for {} stacks", stackWithZValuesList.size());

        final JavaRDD<StackWithZValues> rddStackWithZValues = sparkContext.parallelize(stackWithZValuesList);

        final Function<StackWithZValues, UnconnectedMFOVPairsForStack> findFunction = stackWithZ -> {

            LogUtilities.setupExecutorLog4j(stackWithZ.toString());

            final StackId stackId = stackWithZ.getStackId();
            final RenderDataClient localDataClient = new RenderDataClient(baseDataUrl,
                                                                          stackId.getOwner(),
                                                                          stackId.getProject());
            final org.janelia.render.client.multisem.UnconnectedCrossMFOVClient jClient = parameters.buildJavaClient();

            return jClient.findUnconnectedMFOVs(stackWithZ,
                                                multiProject.deriveMatchCollectionNamesFromProject,
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

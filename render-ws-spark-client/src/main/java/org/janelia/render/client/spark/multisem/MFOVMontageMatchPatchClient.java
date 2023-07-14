package org.janelia.render.client.spark.multisem;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.multisem.StackMFOVWithZValues;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.multisem.Utilities;
import org.janelia.render.client.parameter.AlignmentPipelineParameters;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MFOVMontageMatchPatchParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for patching matches missing from adjacent SFOV tile pairs within the same MFOV and z layer.
 * Core logic is implemented in {@link org.janelia.render.client.multisem.MFOVMontageMatchPatchClient}.
 *
 * @author Eric Trautman
 */
public class MFOVMontageMatchPatchClient
        implements Serializable {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public MultiProjectParameters multiProject = new MultiProjectParameters();

        @Parameter(
                names = "--matchPatchJson",
                description = "JSON file where match patch parameters are defined",
                required = true)
        public String matchPatchJson;

        /** @return client specific parameters populated from specified alignment pipeline parameters. */
        public static Parameters fromPipeline(final AlignmentPipelineParameters alignmentPipelineParameters) {
            final Parameters derivedParameters = new Parameters();
            derivedParameters.multiProject = alignmentPipelineParameters.getMultiProject();
            // NOTE: matchPatch parameters should/will be loaded from alignmentPipelineParameters directly
            //       instead of from matchPatchJson file
            return derivedParameters;
        }

    }


    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                final MFOVMontageMatchPatchClient client = new MFOVMontageMatchPatchClient(parameters);
                client.run();
            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;

    public MFOVMontageMatchPatchClient(final Parameters parameters) throws IllegalArgumentException {
        LOG.info("init: parameters={}", parameters);
        this.parameters = parameters;
    }

    public void run() throws IOException {

        final SparkConf conf = new SparkConf().setAppName("MFOVMontageMatchPatchClient");

        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            final String sparkAppId = sparkContext.getConf().getAppId();
            LOG.info("run: appId is {}", sparkAppId);

            final MFOVMontageMatchPatchParameters patchParameters =
                    MFOVMontageMatchPatchParameters.fromJsonFile(parameters.matchPatchJson);

            patchPairs(sparkContext, patchParameters);
        }
    }

    public void patchPairs(final JavaSparkContext sparkContext,
                           final MFOVMontageMatchPatchParameters patchParameters)
            throws IOException {

        LOG.info("patchPairs: entry");

        final MultiProjectParameters multiProject = parameters.multiProject;
        final RenderDataClient sourceDataClient = multiProject.getDataClient();
        final String baseDataUrl = sourceDataClient.getUrls().getBaseDataUrl();

        final List<StackWithZValues> stackWithZValuesList = multiProject.stackIdWithZ.getStackWithZList(sourceDataClient);
        if (stackWithZValuesList.size() == 0) {
            throw new IllegalArgumentException("no stack z-layers match parameters");
        }

        final List<StackMFOVWithZValues> stackMFOVWithZValuesList = new ArrayList<>();
        for (final StackWithZValues stackWithZValues : stackWithZValuesList) {
            final StackId stackId = stackWithZValues.getStackId();
            final RenderDataClient dataClient = sourceDataClient.buildClient(stackId.getOwner(), stackId.getProject());

            final List<String> mFOVIdList = Utilities.getMFOVNames(dataClient,
                                                                   stackId.getStack(),
                                                                   stackWithZValues.getFirstZ());
            LOG.info("patchPairs: found {} MFOVs in {}", mFOVIdList.size(), stackWithZValues);

            for (final String mFOVId : mFOVIdList) {
                stackMFOVWithZValuesList.add(new StackMFOVWithZValues(stackWithZValues, mFOVId));
            }
        }

        LOG.info("patchPairs: distributing tasks for {} MFOVs", stackMFOVWithZValuesList.size());

        final JavaRDD<StackMFOVWithZValues> rddStackMFOVWithZValues = sparkContext.parallelize(stackMFOVWithZValuesList);

        final Function<StackMFOVWithZValues, Void> patchFunction = stackMOFVWithZ -> {

            LogUtilities.setupExecutorLog4j("MFOV:" + stackMOFVWithZ.getmFOVId());

            final org.janelia.render.client.multisem.MFOVMontageMatchPatchClient.Parameters javaPatchClientParameters =
                    new org.janelia.render.client.multisem.MFOVMontageMatchPatchClient.Parameters();

            javaPatchClientParameters.patch = patchParameters;
            javaPatchClientParameters.patch.setMultiFieldOfViewId(stackMOFVWithZ.getmFOVId());

            final StackId stackId = stackMOFVWithZ.getStackId();
            final RenderDataClient defaultDataClient = new RenderDataClient(baseDataUrl,
                                                                            stackId.getOwner(),
                                                                            stackId.getProject());

            final MatchCollectionId matchCollectionId = multiProject.getMatchCollectionIdForStack(stackId);

            final org.janelia.render.client.multisem.MFOVMontageMatchPatchClient javaPatchClient =
                    new org.janelia.render.client.multisem.MFOVMontageMatchPatchClient(javaPatchClientParameters);

            javaPatchClient.deriveAndSaveMatchesForUnconnectedPairsInStack(defaultDataClient,
                                                                           stackMOFVWithZ,
                                                                           matchCollectionId,
                                                                           matchCollectionId.getName());
            return null;
        };

        final JavaRDD<Void> rddPatch = rddStackMFOVWithZValues.map(patchFunction);
        rddPatch.collect();

        LOG.info("patchPairs: collected rddPatch");
        LOG.info("patchPairs: exit");
    }

    private static final Logger LOG = LoggerFactory.getLogger(MFOVMontageMatchPatchClient.class);
}

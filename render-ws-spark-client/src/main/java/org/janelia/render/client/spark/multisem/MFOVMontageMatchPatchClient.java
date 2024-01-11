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
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.multisem.StackMFOVWithZValues;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MFOVMontageMatchPatchParameters;
import org.janelia.render.client.parameter.MultiStackParameters;
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
public class MFOVMontageMatchPatchClient
        implements Serializable, AlignmentPipelineStep {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public MultiStackParameters multiStack = new MultiStackParameters();

        @Parameter(
                names = "--matchPatchJson",
                description = "JSON file where match patch parameters are defined",
                required = true)
        public String matchPatchJson;
    }

    /** Run the client with command line parameters. */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {
                final Parameters parameters = new Parameters();
                parameters.parse(args);
                final MFOVMontageMatchPatchClient client = new MFOVMontageMatchPatchClient();
                client.createContextAndRun(parameters);
            }
        };
        clientRunner.run();
    }

    /** Empty constructor required for alignment pipeline steps. */
    public MFOVMontageMatchPatchClient() {
    }

    /** Create a spark context and run the client with the specified parameters. */
    public void createContextAndRun(final Parameters clientParameters) throws IOException {
        final SparkConf conf = new SparkConf().setAppName(getClass().getSimpleName());
        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            LOG.info("createContextAndRun: appId is {}", sparkContext.getConf().getAppId());
            final MFOVMontageMatchPatchParameters patchParameters =
                    MFOVMontageMatchPatchParameters.fromJsonFile(clientParameters.matchPatchJson);
            patchPairs(sparkContext, clientParameters.multiStack, patchParameters);
        }
    }

    /** Validates the specified pipeline parameters are sufficient. */
    @Override
    public void validatePipelineParameters(final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException {
        AlignmentPipelineParameters.validateRequiredElementExists("mfovMontagePatch",
                                                                  pipelineParameters.getMfovMontagePatch());
    }

    /** Run the client as part of an alignment pipeline. */
    public void runPipelineStep(final JavaSparkContext sparkContext,
                                final AlignmentPipelineParameters pipelineParameters)
            throws IOException {
        patchPairs(sparkContext,
                   pipelineParameters.getMultiStack(),
                   pipelineParameters.getMfovMontagePatch());
    }


    private void patchPairs(final JavaSparkContext sparkContext,
                            final MultiStackParameters multiStackParameters,
                            final MFOVMontageMatchPatchParameters patchParameters)
            throws IOException {

        LOG.info("patchPairs: entry, multiStackParameters={}, patchParameters={}",
                 multiStackParameters, patchParameters);

        patchPairsForPass(sparkContext, multiStackParameters, patchParameters, 1);

        if (patchParameters.secondPassDerivedMatchWeight != null) {
            patchParameters.setWeightsForSecondPass();
            patchPairsForPass(sparkContext, multiStackParameters, patchParameters, 2);
        }

        LOG.info("patchPairs: exit");
    }

    private void patchPairsForPass(final JavaSparkContext sparkContext,
                                   final MultiStackParameters multiStackParameters,
                                   final MFOVMontageMatchPatchParameters patchParameters,
                                   final int passNumber)
            throws IOException {

        final String passName = "pass" + passNumber;

        LOG.info("patchPairsForPass: entry, {}", passName);

        final String baseDataUrl = multiStackParameters.getBaseDataUrl();
        final List<StackMFOVWithZValues> stackMFOVWithZValuesList =
                multiStackParameters.buildListOfStackMFOVWithAllZ(patchParameters.getMultiFieldOfViewId());

        LOG.info("patchPairsForPass: {}, distributing tasks for {} MFOVs", passName, stackMFOVWithZValuesList.size());

        final JavaRDD<StackMFOVWithZValues> rddStackMFOVWithZValues = sparkContext.parallelize(stackMFOVWithZValuesList);

        final Function<StackMFOVWithZValues, Void> patchFunction = stackMOFVWithZ -> {

            LogUtilities.setupExecutorLog4j("MFOV:" + stackMOFVWithZ.getmFOVId() + ":" + passName);

            final org.janelia.render.client.multisem.MFOVMontageMatchPatchClient.Parameters javaPatchClientParameters =
                    new org.janelia.render.client.multisem.MFOVMontageMatchPatchClient.Parameters();

            javaPatchClientParameters.patch = patchParameters.withMultiFieldOfViewId(stackMOFVWithZ.getmFOVId());

            final StackId stackId = stackMOFVWithZ.getStackId();
            final RenderDataClient defaultDataClient = new RenderDataClient(baseDataUrl,
                                                                            stackId.getOwner(),
                                                                            stackId.getProject());

            final MatchCollectionId matchCollectionId = multiStackParameters.getMatchCollectionIdForStack(stackId);

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

        LOG.info("patchPairsForPass: {}, collected rddPatch", passName);
        LOG.info("patchPairsForPass: {}, exit", passName);
    }

    private static final Logger LOG = LoggerFactory.getLogger(MFOVMontageMatchPatchClient.class);
}

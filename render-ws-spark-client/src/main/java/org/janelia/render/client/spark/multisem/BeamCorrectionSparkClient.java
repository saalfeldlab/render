package org.janelia.render.client.spark.multisem;

import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.multisem.BeamCorrectionClient;
import org.janelia.render.client.parameter.BeamCorrectionParameters;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineParameters;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStep;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStepId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for applying the pre-computed degree-0 beam-homogenization correction to multi-SEM stacks.
 *
 * @see BeamCorrectionClient
 */
public class BeamCorrectionSparkClient
        implements Serializable, AlignmentPipelineStep {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public MultiProjectParameters multiProject = new MultiProjectParameters();

        @ParametersDelegate
        public BeamCorrectionParameters beamCorrection = new BeamCorrectionParameters();
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {
                final Parameters parameters = new Parameters();
                parameters.parse(args);
                parameters.beamCorrection.validate();

                LOG.info("runClient: entry, parameters={}", parameters);

                final BeamCorrectionSparkClient client = new BeamCorrectionSparkClient();
                client.createContextAndRun(parameters);
            }
        };
        clientRunner.run();
    }

    public BeamCorrectionSparkClient() {
    }

    /**
     * Create a spark context and run the client with the specified parameters.
     */
    public void createContextAndRun(final Parameters clientParameters)
            throws IOException {
        final SparkConf conf = new SparkConf().setAppName(getClass().getSimpleName());
        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {

            LOG.info("createContextAndRun: appId is {}", sparkContext.getConf().getAppId());

            correctBeamIntensity(sparkContext,
                                 clientParameters.multiProject,
                                 clientParameters.beamCorrection);
        }
    }

    /** Validates the specified pipeline parameters are sufficient. */
    @Override
    public void validatePipelineParameters(final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException {
        final BeamCorrectionParameters beamCorrection = pipelineParameters.getBeamCorrection();
        AlignmentPipelineParameters.validateRequiredElementExists("beamCorrection",
                                                                  beamCorrection);
        beamCorrection.validate();
    }

    /** Run the client as part of an alignment pipeline. */
    @Override
    public void runPipelineStep(final JavaSparkContext sparkContext,
                                final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException, IOException {

        final MultiProjectParameters multiProject =
                pipelineParameters.getMultiProject(pipelineParameters.getRawNamingGroup());

        correctBeamIntensity(sparkContext,
                             multiProject,
                             pipelineParameters.getBeamCorrection());
    }

    @Override
    public AlignmentPipelineStepId getDefaultStepId() {
        return AlignmentPipelineStepId.CORRECT_BEAM_INTENSITY;
    }

    public void correctBeamIntensity(final JavaSparkContext sparkContext,
                                     final MultiProjectParameters multiProject,
                                     final BeamCorrectionParameters beamCorrection) throws IOException {

        final String baseDataUrl = multiProject.getBaseDataUrl();

        // all z values of each stack are corrected, so only the stack ids are needed here
        final List<StackId> stackIdList = multiProject.stackIdWithZ.getStackIdList(multiProject.getDataClient());

        LOG.info("correctBeamIntensity: entry, distributing {} stacks with beamCorrection {}",
                 stackIdList.size(), beamCorrection);

        if (stackIdList.isEmpty()) {
            throw new IllegalArgumentException("no stacks match the specified parameters");
        }

        final JavaRDD<StackId> rddStackIds = sparkContext.parallelize(stackIdList);

        rddStackIds.foreach(stackId -> {

            LogUtilities.setupExecutorLog4j(stackId.toDevString());

            final RenderDataClient executorDataClient = new RenderDataClient(baseDataUrl,
                                                                             stackId.getOwner(),
                                                                             stackId.getProject());

            new BeamCorrectionClient().correctStack(executorDataClient,
                                                    stackId.getStack(),
                                                    beamCorrection,
                                                    true);
        });

        LOG.info("correctBeamIntensity: exit, corrected {} stacks", stackIdList.size());
    }

    private static final Logger LOG = LoggerFactory.getLogger(BeamCorrectionSparkClient.class);
}

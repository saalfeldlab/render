package org.janelia.render.client.spark.pipeline;

import com.beust.jcommander.Parameter;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for running an ordered list of alignment steps in one go.
 * Parameters for the alignment steps are defined in a JSON file.
 *
 * @author Eric Trautman
 */
public class AlignmentPipelineClient
        implements Serializable {

    public static class Parameters extends CommandLineParameters {
        @Parameter(
                names = "--pipelineJson",
                description = "JSON file where pipeline parameters are defined",
                required = true)
        public String pipelineJson;
    }

    /** Run the client with command line parameters. */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {
                final Parameters parameters = new Parameters();
                parameters.parse(args);
                final AlignmentPipelineClient client = new AlignmentPipelineClient();
                client.createContextAndRun(parameters);
            }
        };
        clientRunner.run();
    }

    private AlignmentPipelineClient() {
    }

    /** Create a spark context and run the client with the specified parameters. */
    public void createContextAndRun(final Parameters clientParameters) throws IOException, IllegalArgumentException {
        final SparkConf conf = new SparkConf().setAppName(getClass().getSimpleName());
        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            LOG.info("createContextAndRun: appId is {}", sparkContext.getConf().getAppId());
            final AlignmentPipelineParameters parsedParameters =
                    AlignmentPipelineParameters.fromJsonFile(clientParameters.pipelineJson);
            runPipeline(sparkContext, parsedParameters);
        }
    }

    private void runPipeline(final JavaSparkContext sparkContext,
                             final AlignmentPipelineParameters alignmentPipelineParameters)
            throws IOException, IllegalArgumentException {

        LOG.info("runPipeline: entry, alignmentPipelineParameters={}", alignmentPipelineParameters.toJson());

        final List<AlignmentPipelineStep> pipelineStepClients = alignmentPipelineParameters.buildStepClients();
        pipelineStepClients.forEach(stepClient -> stepClient.validatePipelineParameters(alignmentPipelineParameters));

        for (final AlignmentPipelineStep stepClient : pipelineStepClients) {
            stepClient.runPipelineStep(sparkContext, alignmentPipelineParameters);
        }

        LOG.info("runPipeline: exit");
    }

    private static final Logger LOG = LoggerFactory.getLogger(AlignmentPipelineClient.class);
}

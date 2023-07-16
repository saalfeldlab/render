package org.janelia.render.client.spark.pipeline;

import com.beust.jcommander.Parameter;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.parameter.AlignmentPipelineParameters;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.spark.MipmapClient;
import org.janelia.render.client.spark.match.MultiStagePointMatchClient;
import org.janelia.render.client.spark.multisem.MFOVMontageMatchPatchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for running various alignment stages in one go.
 * Parameters for the alignment stages to run are defined in a JSON file.
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

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final AlignmentPipelineClient client = new AlignmentPipelineClient(parameters);
                client.run();

            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;

    private AlignmentPipelineClient(final Parameters parameters) throws IllegalArgumentException {
        this.parameters = parameters;
    }

    public void run() throws IOException {

        final SparkConf conf = new SparkConf().setAppName("AlignmentPipelineClient");

        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            final String sparkAppId = sparkContext.getConf().getAppId();
            LOG.info("run: appId is {}", sparkAppId);
            final AlignmentPipelineParameters parsedParameters =
                    AlignmentPipelineParameters.fromJsonFile(parameters.pipelineJson);
            runWithContext(sparkContext, parsedParameters);
        }
    }

    public void runWithContext(final JavaSparkContext sparkContext,
                               final AlignmentPipelineParameters alignmentPipelineParameters)
            throws IOException {

        LOG.info("runWithContext: entry, alignmentPipelineParameters={}", alignmentPipelineParameters.toJson());

        if (alignmentPipelineParameters.hasMipmapParameters()) {
            final MipmapClient.Parameters p =
                    MipmapClient.Parameters.fromPipeline(alignmentPipelineParameters);
            final MipmapClient mipmapClient = new MipmapClient(p);
            mipmapClient.runWithContext(sparkContext);
        }

        if (alignmentPipelineParameters.hasMatchParameters()) {
            final MultiStagePointMatchClient.Parameters p =
                    MultiStagePointMatchClient.Parameters.fromPipeline(alignmentPipelineParameters);
            final MultiStagePointMatchClient matchClient = new MultiStagePointMatchClient(p);
            final MultiProjectParameters multiProject = alignmentPipelineParameters.getMultiProject();
            final List<StackWithZValues> stackWithZValuesList = multiProject.buildStackWithZValuesList();
            matchClient.generatePairsAndMatchesForRunList(sparkContext,
                                                          stackWithZValuesList,
                                                          alignmentPipelineParameters.getMatchRunList());
        }

        if (alignmentPipelineParameters.hasMfovMontagePatchParameters()) {
            final MFOVMontageMatchPatchClient.Parameters p =
                    MFOVMontageMatchPatchClient.Parameters.fromPipeline(alignmentPipelineParameters);
            final MFOVMontageMatchPatchClient matchPatchClient = new MFOVMontageMatchPatchClient(p);
            matchPatchClient.patchPairs(sparkContext, alignmentPipelineParameters.getMfovMontagePatch());
        }

        LOG.info("runWithContext: exit");
    }

    private static final Logger LOG = LoggerFactory.getLogger(AlignmentPipelineClient.class);
}
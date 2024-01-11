package org.janelia.render.client.spark;

import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineParameters;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MipmapParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for generating mipmap files into a {@link org.janelia.alignment.spec.stack.MipmapPathBuilder}
 * directory structure.
 *
 * @author Eric Trautman
 */
public class MipmapClient
        implements Serializable, AlignmentPipelineStep {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @ParametersDelegate
        MipmapParameters mipmap = new MipmapParameters();
    }

    /** Run the client with command line parameters. */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {
                final Parameters parameters = new Parameters();
                parameters.parse(args);
                final MipmapClient client = new MipmapClient();
                client.createContextAndRun(parameters);
            }
        };
        clientRunner.run();
    }

    /** Empty constructor required for alignment pipeline steps. */
    public MipmapClient() {
    }

    /** Create a spark context and run the client with the specified parameters. */
    public void createContextAndRun(final Parameters mipmapParameters) throws IOException {
        final SparkConf conf = new SparkConf().setAppName(getClass().getSimpleName());
        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            LOG.info("createContextAndRun: appId is {}", sparkContext.getConf().getAppId());
            generateMipmaps(sparkContext, mipmapParameters);
        }
    }

    /** Validates the specified pipeline parameters are sufficient. */
    @Override
    public void validatePipelineParameters(final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException {
        AlignmentPipelineParameters.validateRequiredElementExists("mipmap",
                                                                  pipelineParameters.getMipmap());
    }

    /** Run the client as part of an alignment pipeline. */
    public void runPipelineStep(final JavaSparkContext sparkContext,
                                final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException, IOException {

        final MultiProjectParameters multiProject = pipelineParameters.getMultiProject(pipelineParameters.getRawNamingGroup());
        final Parameters clientParameters = new Parameters();
        clientParameters.renderWeb = new RenderWebServiceParameters(multiProject.baseDataUrl,
                                                                    multiProject.owner,
                                                                    multiProject.project);
        clientParameters.mipmap = pipelineParameters.getMipmap();
        clientParameters.mipmap.setStackIdWithZIfUndefined(multiProject.stackIdWithZ);

        generateMipmaps(sparkContext, clientParameters);
    }

    /** Run the client with the specified spark context and parameters. */
    private void generateMipmaps(final JavaSparkContext sparkContext,
                                 final Parameters clientParameters)
            throws IOException {

        LOG.info("generateMipmaps: entry, clientParameters={}", clientParameters);

        final RenderDataClient sourceDataClient = clientParameters.renderWeb.getDataClient();

        final List<StackWithZValues> batchedList =
                clientParameters.mipmap.stackIdWithZ.buildListOfStackWithBatchedZ(sourceDataClient);
        final JavaRDD<StackWithZValues> rddStackIdWithZValues = sparkContext.parallelize(batchedList);

        final Function<StackWithZValues, Integer> mipmapFunction = stackIdWithZ -> {
            LogUtilities.setupExecutorLog4j(stackIdWithZ.toString());
            final org.janelia.render.client.MipmapClient mc =
                    new org.janelia.render.client.MipmapClient(clientParameters.renderWeb,
                                                               clientParameters.mipmap);
            return mc.processMipmapsForZ(stackIdWithZ.getStackId(),
                                         stackIdWithZ.getFirstZ());
        };

        final JavaRDD<Integer> rddTileCounts = rddStackIdWithZValues.map(mipmapFunction);

        final List<Integer> tileCountList = rddTileCounts.collect();
        long total = 0;
        for (final Integer tileCount : tileCountList) {
            total += tileCount;
        }

        LOG.info("generateMipmaps: collected stats");
        LOG.info("generateMipmaps: generated mipmaps for {} tiles", total);

        final org.janelia.render.client.MipmapClient mc =
                new org.janelia.render.client.MipmapClient(clientParameters.renderWeb,
                                                           clientParameters.mipmap);
        final List<StackId> distinctStackIds = batchedList.stream()
                .map(StackWithZValues::getStackId)
                .distinct()
                .collect(Collectors.toList());
        for (final StackId stackId : distinctStackIds) {
            mc.updateMipmapPathBuilderForStack(stackId);
        }

        LOG.info("generateMipmaps: exit");
    }

    private static final Logger LOG = LoggerFactory.getLogger(MipmapClient.class);
}

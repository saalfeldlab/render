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
import org.janelia.render.client.parameter.AlignmentPipelineParameters;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MipmapParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for generating mipmap files into a {@link org.janelia.alignment.spec.stack.MipmapPathBuilder}
 * directory structure.
 *
 * @author Eric Trautman
 */
public class MipmapClient
        implements Serializable {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @ParametersDelegate
        MipmapParameters mipmap = new MipmapParameters();

        /** @return client specific parameters populated from specified alignment pipeline parameters. */
        public static Parameters fromPipeline(final AlignmentPipelineParameters alignmentPipelineParameters) {
            final MultiProjectParameters multiProject = alignmentPipelineParameters.getMultiProject();
            final Parameters derivedParameters = new Parameters();
            derivedParameters.renderWeb = new RenderWebServiceParameters(multiProject.baseDataUrl,
                                                                         multiProject.owner,
                                                                         multiProject.project);
            derivedParameters.mipmap = alignmentPipelineParameters.getMipmap();
            derivedParameters.mipmap.setStackIdWithZIfUndefined(multiProject.stackIdWithZ);
            return derivedParameters;
        }
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                final MipmapClient client = new MipmapClient(parameters);
                client.run();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    public MipmapClient(final Parameters parameters) {
        LOG.info("init: parameters={}", parameters);
        this.parameters = parameters;
    }

    public void run() throws IOException {
        final SparkConf conf = new SparkConf().setAppName("MipmapClient");
        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            final String sparkAppId = sparkContext.getConf().getAppId();
            LOG.info("run: appId is {}", sparkAppId);
            runWithContext(sparkContext);
        }
    }

    public void runWithContext(final JavaSparkContext sparkContext)
            throws IOException {

        LOG.info("runWithContext: entry");

        final RenderDataClient sourceDataClient = parameters.renderWeb.getDataClient();

        final List<StackWithZValues> stackIdWithZValues =
                parameters.mipmap.stackIdWithZ.getStackWithZList(sourceDataClient);

        if (stackIdWithZValues.size() == 0) {
            throw new IllegalArgumentException("no stack z-layers match parameters");
        }

        final JavaRDD<StackWithZValues> rddStackIdWithZValues = sparkContext.parallelize(stackIdWithZValues);

        final Function<StackWithZValues, Integer> mipmapFunction = stackIdWithZ -> {
            LogUtilities.setupExecutorLog4j(stackIdWithZ.toString());
            final org.janelia.render.client.MipmapClient mc =
                    new org.janelia.render.client.MipmapClient(parameters.renderWeb,
                                                               parameters.mipmap);
            return mc.processMipmapsForZ(stackIdWithZ.getStackId(),
                                         stackIdWithZ.getFirstZ());
        };

        final JavaRDD<Integer> rddTileCounts = rddStackIdWithZValues.map(mipmapFunction);

        final List<Integer> tileCountList = rddTileCounts.collect();
        long total = 0;
        for (final Integer tileCount : tileCountList) {
            total += tileCount;
        }

        LOG.info("run: collected stats");
        LOG.info("run: generated mipmaps for {} tiles", total);

        final org.janelia.render.client.MipmapClient mc =
                new org.janelia.render.client.MipmapClient(parameters.renderWeb,
                                                           parameters.mipmap);
        final List<StackId> distinctStackIds = stackIdWithZValues.stream()
                .map(StackWithZValues::getStackId)
                .distinct()
                .collect(Collectors.toList());
        for (final StackId stackId : distinctStackIds) {
            mc.updateMipmapPathBuilderForStack(stackId);
        }

        LOG.info("runWithContext: exit");
    }

    private static final Logger LOG = LoggerFactory.getLogger(MipmapClient.class);
}

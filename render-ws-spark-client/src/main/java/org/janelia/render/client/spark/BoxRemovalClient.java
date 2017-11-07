package org.janelia.render.client.spark;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.ZRangeParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for removing images from a CATMAID LargeDataTileSource directory structure that looks like this:
 * <pre>
 *         [root directory]/[tile width]x[tile height]/[level]/[z]/[row]/[col].[format]
 * </pre>
 *
 * @author Eric Trautman
 */
public class BoxRemovalClient implements Serializable {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @ParametersDelegate
        public ZRangeParameters layerRange = new ZRangeParameters();

        @Parameter(
                names = "--stack",
                description = "Stack name",
                required = true)
        public String stack;

        @Parameter(
                names = "--stackDirectory",
                description = "Stack directory containing boxes to remove (e.g. /tier2/flyTEM/nobackup/rendered_boxes/FAFB00/v7_align_tps/8192x8192)",
                required = true)
        public String stackDirectory;

        @Parameter(
                names = "--minLevel",
                description = "Minimum mipmap level to remove",
                required = false)
        public int minLevel = 0;

        @Parameter(
                names = "--maxLevel",
                description = "Maximum mipmap level to remove (values > 8 will also delete small overview images)",
                required = false)
        public int maxLevel = 9;

    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final BoxRemovalClient client = new BoxRemovalClient(parameters);
                client.run();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    public BoxRemovalClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    public void run()
            throws IOException, URISyntaxException {

        final SparkConf conf = new SparkConf().setAppName("BoxRemovalClient");
        final JavaSparkContext sparkContext = new JavaSparkContext(conf);

        final String sparkAppId = sparkContext.getConf().getAppId();
        final String executorsJson = LogUtilities.getExecutorsApiJson(sparkAppId);

        LOG.info("run: appId is {}, executors data is {}", sparkAppId, executorsJson);

        final RenderDataClient sourceDataClient = parameters.renderWeb.getDataClient();

        final List<Double> zValues = sourceDataClient.getStackZValues(parameters.stack,
                                                                      parameters.layerRange.minZ,
                                                                      parameters.layerRange.maxZ);

        if (zValues.size() == 0) {
            throw new IllegalArgumentException("stack does not contain any matching z values");
        }


        final JavaRDD<Double> rddZValues = sparkContext.parallelize(zValues);

        final Function<Double, Integer> generateBoxesFunction = (Function<Double, Integer>) z -> {

            LogUtilities.setupExecutorLog4j("z " + z);

            final org.janelia.render.client.BoxRemovalClient boxRemovalClient =
                    new org.janelia.render.client.BoxRemovalClient(parameters.stackDirectory,
                                                                   parameters.minLevel,
                                                                   parameters.maxLevel);
            boxRemovalClient.removeBoxesForZ(z);
            return 1;
        };

        final JavaRDD<Integer> rddLayerCounts = rddZValues.map(generateBoxesFunction);

        final List<Integer> layerCountList = rddLayerCounts.collect();
        long total = 0;
        for (final Integer layerCount : layerCountList) {
            total += layerCount;
        }

        LOG.info("run: collected stats");
        LOG.info("run: removed boxes for {} layers", total);

        sparkContext.stop();
    }

    private static final Logger LOG = LoggerFactory.getLogger(BoxRemovalClient.class);
}

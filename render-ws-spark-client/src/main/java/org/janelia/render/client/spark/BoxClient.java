package org.janelia.render.client.spark;

import com.beust.jcommander.Parameter;

import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.janelia.render.client.BoxGenerator;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for rendering uniform (but arbitrarily sized) boxes (derived tiles) to disk for one or more layers.
 * See {@link BoxGenerator} for implementation details.
 *
 * @author Eric Trautman
 */
public class BoxClient
        implements Serializable {

    @SuppressWarnings("ALL")
    private static class Parameters extends BoxGenerator.Parameters {

        // NOTE: almost everything is defined in BoxGenerator.Parameters

        @Parameter(
                names = "--minZ",
                description = "Minimum Z value for sections to be rendered",
                required = false)
        private Double minZ;

        @Parameter(
                names = "--maxZ",
                description = "Maximum Z value for sections to be rendered",
                required = false)
        private Double maxZ;

    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args, BoxClient.class);

                LOG.info("runClient: entry, parameters={}", parameters);

                final BoxClient client = new BoxClient(parameters);
                client.run();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    public BoxClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    public void run()
            throws IOException, URISyntaxException {

        final SparkConf conf = new SparkConf().setAppName("BoxClient");
        final JavaSparkContext sparkContext = new JavaSparkContext(conf);

        final String sparkAppId = sparkContext.getConf().getAppId();
        final String executorsJson = LogUtilities.getExecutorsApiJson(sparkAppId);

        LOG.info("run: appId is {}, executors data is {}", sparkAppId, executorsJson);


        final RenderDataClient sourceDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                                       parameters.owner,
                                                                       parameters.project);

        final List<Double> zValues = sourceDataClient.getStackZValues(parameters.getStack(),
                                                                      parameters.minZ,
                                                                      parameters.maxZ);

        if (zValues.size() == 0) {
            throw new IllegalArgumentException("source stack does not contain any matching z values");
        }


        final JavaRDD<Double> rddZValues = sparkContext.parallelize(zValues);

        final Function<Double, Integer> generateBoxesFunction = new Function<Double, Integer>() {

            final
            @Override
            public Integer call(final Double z)
                    throws Exception {

                LogUtilities.setupExecutorLog4j("z " + z);

                final BoxGenerator boxGenerator = new BoxGenerator(parameters);
                boxGenerator.createEmptyImageFile();
                boxGenerator.generateBoxesForZ(z);
                return 1;
            }
        };

        final JavaRDD<Integer> rddLayerCounts = rddZValues.map(generateBoxesFunction);

        final List<Integer> layerCountList = rddLayerCounts.collect();
        long total = 0;
        for (final Integer layerCount : layerCountList) {
            total += layerCount;
        }

        LOG.info("run: collected stats");
        LOG.info("run: generated boxes for {} layers", total);

        sparkContext.stop();
    }

    private static final Logger LOG = LoggerFactory.getLogger(BoxClient.class);
}

package org.janelia.render.client.spark;

import com.beust.jcommander.Parameter;

import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
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

    @SuppressWarnings("ALL")
    private static class Parameters extends org.janelia.render.client.MipmapClient.CommonParameters {

        // NOTE: most parameters defined in MipmapClient.CommonParameters

        @Parameter(
                names = "--minZ",
                description = "Minimum Z value for mipmap tiles",
                required = false)
        private Double minZ;

        @Parameter(
                names = "--maxZ",
                description = "Maximum Z value for mipmap tiles",
                required = false)
        private Double maxZ;

    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args, MipmapClient.class);

                LOG.info("runClient: entry, parameters={}", parameters);

                final MipmapClient client = new MipmapClient(parameters);
                client.run();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    public MipmapClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    public void run()
            throws IOException, URISyntaxException {

        final SparkConf conf = new SparkConf().setAppName("MipmapClient");
        final JavaSparkContext sparkContext = new JavaSparkContext(conf);

        final String sparkAppId = sparkContext.getConf().getAppId();
        final String executorsJson = LogUtilities.getExecutorsApiJson(sparkAppId);

        LOG.info("run: appId is {}, executors data is {}", sparkAppId, executorsJson);


        final RenderDataClient sourceDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                                       parameters.owner,
                                                                       parameters.project);

        final List<Double> zValues = sourceDataClient.getStackZValues(parameters.stack,
                                                                      parameters.minZ,
                                                                      parameters.maxZ);

        if (zValues.size() == 0) {
            throw new IllegalArgumentException("source stack does not contain any matching z values");
        }

        final JavaRDD<Double> rddZValues = sparkContext.parallelize(zValues);

        final Function<Double, Integer> mipmapFunction = new Function<Double, Integer>() {

            final
            @Override
            public Integer call(final Double z)
                    throws Exception {

                LogUtilities.setupExecutorLog4j("z " + z);
                final org.janelia.render.client.MipmapClient mc = getCoreMipmapClient(parameters, z);
                return mc.generateMipmapsForZ(z);
            }
        };

        final JavaRDD<Integer> rddTileCounts = rddZValues.map(mipmapFunction);

        final List<Integer> tileCountList = rddTileCounts.collect();
        long total = 0;
        for (final Integer tileCount : tileCountList) {
            total += tileCount;
        }

        LOG.info("run: collected stats");
        LOG.info("run: generated mipmaps for {} tiles", total);

        final org.janelia.render.client.MipmapClient mc = getCoreMipmapClient(parameters, null);
        mc.updateMipmapPathBuilderForStack();

        sparkContext.stop();
    }

    private static org.janelia.render.client.MipmapClient getCoreMipmapClient(final Parameters parameters,
                                                                              final Double z)
            throws IOException {

        final org.janelia.render.client.MipmapClient.Parameters p =
                new org.janelia.render.client.MipmapClient.Parameters(parameters,
                                                                      Collections.singletonList(z));
        return new org.janelia.render.client.MipmapClient(p);
    }

    private static final Logger LOG = LoggerFactory.getLogger(MipmapClient.class);
}

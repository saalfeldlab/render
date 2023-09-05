package org.janelia.render.client.spark.intensityadjust;

import java.io.Serializable;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.intensityadjust.IntensityCorrectionWorker;
import org.janelia.render.client.parameter.IntensityAdjustParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for adjusting same z-layer tile intensities for a stack.
 * Results can be stored as tile filters in a result stack or rendered to disk as corrected "montage-scapes".
 *
 * @author Eric Trautman
 */
public class IntensityCorrectionClient
        implements Serializable {

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final IntensityAdjustParameters parameters = new IntensityAdjustParameters();
                parameters.parse(args);

                if (parameters.correctIn3D()) {
                    throw new UnsupportedOperationException("3D correction is not yet supported for spark runs because we have yet to decide how to partition the work");
                }

                LOG.info("runClient: entry, parameters={}", parameters);

                final SparkConf conf = new SparkConf().setAppName("IntensityAdjustedScapeClient");

                try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {

                    final String sparkAppId = sparkContext.getConf().getAppId();

                    LOG.info("runClient: appId is {}", sparkAppId);

                    final RenderDataClient dataClient = parameters.renderWeb.getDataClient();

                    final IntensityCorrectionWorker worker = new IntensityCorrectionWorker(parameters, dataClient);

                    final JavaRDD<Double> rddSectionData = sparkContext.parallelize(worker.getzValues());

                    final Function<Double, Integer> intensityCorrectionFunction =
                            z -> {
                                final int integralZ = z.intValue();
                                LogUtilities.setupExecutorLog4j("z " + integralZ);

                                final RenderDataClient localDataClient = parameters.renderWeb.getDataClient();
                                worker.correctZRange(localDataClient, z, z);

                                return 1;
                            };

                    final JavaRDD<Integer> rddLayerCounts = rddSectionData.map(intensityCorrectionFunction);

                    final long processedLayerCount = rddLayerCounts.collect().stream().reduce(0, Integer::sum);

                    LOG.info("runClient: collected stats");

                    worker.completeCorrectedStackAsNeeded(dataClient);

                    LOG.info("runClient: corrected {} layers", processedLayerCount);
                }
            }
        };
        clientRunner.run();
    }

    private static final Logger LOG = LoggerFactory.getLogger(IntensityCorrectionClient.class);
}

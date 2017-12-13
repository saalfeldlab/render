package org.janelia.render.client.spark;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.janelia.alignment.spec.SectionData;
import org.janelia.render.client.BoxGenerator;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MaterializedBoxParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.ZRangeParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import scala.Tuple2;

/**
 * Spark client for rendering uniform (but arbitrarily sized) boxes (derived tiles) to disk for one or more layers.
 * See {@link BoxGenerator} for implementation details.
 *
 * @author Eric Trautman
 */
public class BoxClient implements Serializable {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @ParametersDelegate
        public MaterializedBoxParameters box = new MaterializedBoxParameters();

        @ParametersDelegate
        public ZRangeParameters layerRange = new ZRangeParameters();

        @Parameter(
                names = "--z",
                description = "Explicit z values for sections to be rendered",
                required = false,
                variableArity = true) // e.g. --z 20.0 21.0 22.0
        public List<Double> zValues;

        public Set<Double> getZValues() {
            return (zValues == null) ? Collections.emptySet() : new HashSet<>(zValues);
        }

    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

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


        final RenderDataClient sourceDataClient = parameters.renderWeb.getDataClient();

        final List<SectionData> sectionDataList = sourceDataClient.getStackSectionData(parameters.box.stack,
                                                                                       parameters.layerRange.minZ,
                                                                                       parameters.layerRange.maxZ,
                                                                                       parameters.getZValues());
        if (sectionDataList.size() == 0) {
            throw new IllegalArgumentException("source stack does not contain any matching z values");
        }

        // batch layers by tile count in attempt to distribute work load as evenly as possible across cores
        final int numberOfCores = sparkContext.defaultParallelism();
        final LayerDistributor layerDistributor = new LayerDistributor(numberOfCores);
        final List<List<Double>> batchedZValues = layerDistributor.distribute(sectionDataList);

        final List<Tuple2<List<Double>, MaterializedBoxParameters>> zBatchWithParametersList =
                new ArrayList<>(batchedZValues.size());

        MaterializedBoxParameters lastGroupParameters = parameters.box;

        for (final List<Double> zBatch : batchedZValues) {
            if (parameters.box.numberOfRenderGroups == null) {
                zBatchWithParametersList.add(new Tuple2<>(zBatch, parameters.box));
            } else {
                for (int i = 0; i < parameters.box.numberOfRenderGroups; i++) {
                    final MaterializedBoxParameters p =
                            parameters.box.getInstanceForRenderGroup(i + 1,
                                                                     parameters.box.numberOfRenderGroups);
                    zBatchWithParametersList.add(new Tuple2<>(zBatch, p));
                    lastGroupParameters = p;
                }
            }
        }

        // create the emtpy image file up-front
        final BoxGenerator boxGenerator = new BoxGenerator(parameters.renderWeb, lastGroupParameters);
        boxGenerator.createEmptyImageFile();

        final JavaRDD<Tuple2<List<Double>, MaterializedBoxParameters>> rddZValues =
                sparkContext.parallelize(zBatchWithParametersList);

        final Function<Tuple2<List<Double>, MaterializedBoxParameters>, Integer> generateBoxesFunction =
                (Function<Tuple2<List<Double>, MaterializedBoxParameters>, Integer>) zBatchWithParameters -> {

                    final List<Double> zBatch = zBatchWithParameters._1;
                    final MaterializedBoxParameters p = zBatchWithParameters._2;

                    final BoxGenerator boxGenerator1 = new BoxGenerator(parameters.renderWeb, p);

                    for (int i = 0; i < zBatch.size(); i++) {

                        final Double z = zBatch.get(i);
                        if (p.renderGroup == null) {
                            LogUtilities.setupExecutorLog4j("z " + z);
                        } else {
                            LogUtilities.setupExecutorLog4j("z " + z + " (" + p.renderGroup + " of " + p.numberOfRenderGroups + ")");
                        }

                        LOG.info("materializing layer {} of {}, remaining layer z values are {}",
                                 i + 1, zBatch.size(), zBatch.subList(i+1, zBatch.size()));

                        boxGenerator1.generateBoxesForZ(z);
                    }

                    return zBatch.size();
                };

        final JavaRDD<Integer> rddLayerCounts = rddZValues.map(generateBoxesFunction);

        final List<Integer> layerCountList = rddLayerCounts.collect();
        long total = 0;
        for (final Integer layerCount : layerCountList) {
            total += layerCount;
        }

        LOG.info("run: collected stats");
        final String renderGroupsName = parameters.box.numberOfRenderGroups == null ? "layers" : "render groups";
        LOG.info("run: generated boxes for {} {}", total, renderGroupsName);

        LogUtilities.logSparkClusterInfo(sparkContext); // log cluster info again here to add run stats to driver log

        sparkContext.stop();
    }

    private static final Logger LOG = LoggerFactory.getLogger(BoxClient.class);
}

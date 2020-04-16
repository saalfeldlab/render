package org.janelia.render.client.spark;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackMetaData.StackState;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for merging multiple stacks into one.
 *
 * @author Eric Trautman
 */
public class MergeStacksClient
        implements Serializable {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stacksToMerge",
                description = "Name of source stacks to merge",
                required = true,
                variableArity = true)
        public List<String> stacksToMerge;

        @Parameter(
                names = "--targetOwner",
                description = "Name of target stack owner (default is same as source stack owner)"
        )
        private String targetOwner;

        @Parameter(
                names = "--targetProject",
                description = "Name of target stack project (default is same as source stack project)"
        )
        private String targetProject;

        @Parameter(
                names = "--targetStack",
                description = "Name of target stack",
                required = true)
        public String targetStack;

        @Parameter(
                names = "--completeTargetStack",
                description = "Complete the target stack after merging",
                arity = 0)
        public boolean completeTargetStack = false;


        String getTargetOwner() {
            if (targetOwner == null) {
                targetOwner = renderWeb.owner;
            }
            return targetOwner;
        }

        String getTargetProject() {
            if (targetProject == null) {
                targetProject = renderWeb.project;
            }
            return targetProject;
        }

    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final MergeStacksClient client = new MergeStacksClient(parameters);
                client.run();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    private MergeStacksClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    public void run()
            throws IOException {

        final SparkConf conf = new SparkConf().setAppName("MergeStackClient");
        final JavaSparkContext sparkContext = new JavaSparkContext(conf);

        final String sparkAppId = sparkContext.getConf().getAppId();
        final String executorsJson = LogUtilities.getExecutorsApiJson(sparkAppId);

        LOG.info("run: appId is {}, executors data is {}", sparkAppId, executorsJson);

        final RenderDataClient sourceDataClient = parameters.renderWeb.getDataClient();

        final RenderDataClient targetDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                                       parameters.getTargetOwner(),
                                                                       parameters.getTargetProject());

        for (int i = 0; i < parameters.stacksToMerge.size(); i++) {
            final String stack = parameters.stacksToMerge.get(i);
            if (i == 0) {
                final StackMetaData sourceStackMetaData = sourceDataClient.getStackMetaData(stack);
                targetDataClient.setupDerivedStack(sourceStackMetaData, parameters.targetStack);
            }
            copyStack(stack, sparkContext, sourceDataClient);
        }

        if (parameters.completeTargetStack) {
            final RenderDataClient driverTargetDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                                                 parameters.getTargetOwner(),
                                                                                 parameters.getTargetProject());
            driverTargetDataClient.setStackState(parameters.targetStack, StackState.COMPLETE);
        }

        sparkContext.stop();
    }

    public void copyStack(final String stack,
                          final JavaSparkContext sparkContext,
                          final RenderDataClient sourceDataClient)
            throws IOException {

        final List<SectionData> sectionDataList =
                sourceDataClient.getStackSectionData(stack, null,null);

        // batch layers by tile count in attempt to distribute work load as evenly as possible across cores
        final int numberOfCores = sparkContext.defaultParallelism();
        final LayerDistributor layerDistributor = new LayerDistributor(numberOfCores);
        final List<List<Double>> batchedZValues = layerDistributor.distribute(sectionDataList);

        final JavaRDD<List<Double>> rddZValues = sparkContext.parallelize(batchedZValues);

        final Function<List<Double>, Long> copyFunction = (Function<List<Double>, Long>) zBatch -> {

            final RenderDataClient localSourceDataClient = parameters.renderWeb.getDataClient();

            final RenderDataClient localTargetDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                                                parameters.getTargetOwner(),
                                                                                parameters.getTargetProject());

            long processedTileCount = 0;

            for (int i = 0; i < zBatch.size(); i++) {

                final Double z = zBatch.get(i);

                LogUtilities.setupExecutorLog4j("z " + z);

                LOG.info("copyFunction: processing layer {} of {}, next layer z values are {}",
                         i + 1, zBatch.size(), zBatch.subList(i+1, Math.min(zBatch.size(), i+10)));

                final ResolvedTileSpecCollection sourceCollection =
                        localSourceDataClient.getResolvedTiles(stack, z);

                localTargetDataClient.saveResolvedTiles(sourceCollection, parameters.targetStack, z);

                processedTileCount += sourceCollection.getTileCount();
            }

            return processedTileCount;
        };

        final JavaRDD<Long> rddTileCounts = rddZValues.map(copyFunction);

        final List<Long> tileCountList = rddTileCounts.collect();
        long total = 0;
        for (final Long tileCount : tileCountList) {
            total += tileCount;
        }

        LOG.info("run: collected stats");
        LOG.info("run: copied {} tiles", total);
    }

    private static final Logger LOG = LoggerFactory.getLogger(MergeStacksClient.class);
}

package org.janelia.render.client.spark.match;

import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.janelia.alignment.match.ConnectedTileClusterSummaryForStack;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MultiStackParameters;
import org.janelia.render.client.parameter.TileClusterParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineParameters;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for identifying connected tile clusters in a stack.
 *
 * @author Eric Trautman
 */
public class ClusterCountClient
        implements Serializable, AlignmentPipelineStep {

    public static class Parameters extends CommandLineParameters {
        @ParametersDelegate
        public MultiStackParameters multiStack = new MultiStackParameters();

        @ParametersDelegate
        public TileClusterParameters tileCluster = new TileClusterParameters();

        public org.janelia.render.client.ClusterCountClient buildJavaClient() {
            final org.janelia.render.client.ClusterCountClient.Parameters javaClientParameters =
                    new org.janelia.render.client.ClusterCountClient.Parameters();
            javaClientParameters.multiStack = multiStack;
            javaClientParameters.tileCluster = tileCluster;
            return new org.janelia.render.client.ClusterCountClient(javaClientParameters);
        }
    }

    /** Run the client with command line parameters. */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {
                final Parameters parameters = new Parameters();
                parameters.parse(args);
                final ClusterCountClient client = new ClusterCountClient();
                client.createContextAndRun(parameters);
            }
        };
        clientRunner.run();
    }

    /** Empty constructor required for alignment pipeline steps. */
    public ClusterCountClient() {
    }

    /** Create a spark context and run the client with the specified parameters. */
    public void createContextAndRun(final Parameters clientParameters) throws IOException {
        final SparkConf conf = new SparkConf().setAppName(getClass().getSimpleName());
        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            LOG.info("run: appId is {}", sparkContext.getConf().getAppId());
            findConnectedClusters(sparkContext, clientParameters);
        }
    }

    /** Validates the specified pipeline parameters are sufficient. */
    @Override
    public void validatePipelineParameters(final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException {
        AlignmentPipelineParameters.validateRequiredElementExists("tileCluster",
                                                                  pipelineParameters.getTileCluster());
    }

    /** Run the client as part of an alignment pipeline. */
    public void runPipelineStep(final JavaSparkContext sparkContext,
                                final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException, IOException {

        final Parameters clientParameters = new Parameters();
        clientParameters.multiStack = pipelineParameters.getMultiStack(pipelineParameters.getRawNamingGroup());
        clientParameters.tileCluster = pipelineParameters.getTileCluster();

        final List<ConnectedTileClusterSummaryForStack> summaryList =
                findConnectedClusters(sparkContext, clientParameters);

        final List<String> problemStackSummaryStrings = new ArrayList<>();
        for (final ConnectedTileClusterSummaryForStack stackSummary : summaryList) {
            if (stackSummary.hasMultipleClusters() ||
                stackSummary.hasUnconnectedTiles() ||
                stackSummary.hasTooManyConsecutiveUnconnectedEdges()) {
                problemStackSummaryStrings.add(stackSummary.toString());
            }
        }

        if (! problemStackSummaryStrings.isEmpty()) {
            throw new IOException("The following " + problemStackSummaryStrings.size() +
                                  " stacks have match connection issues:\n" +
                                  String.join("\n", problemStackSummaryStrings));
        }
    }

    private List<ConnectedTileClusterSummaryForStack> findConnectedClusters(final JavaSparkContext sparkContext,
                                                                            final Parameters clientParameters)
            throws IOException {

        LOG.info("findConnectedClusters: entry, clientParameters={}", clientParameters);

        final MultiStackParameters multiStackParameters = clientParameters.multiStack;
        final String baseDataUrl = multiStackParameters.getBaseDataUrl();
        final List<StackWithZValues> stackWithZValuesList = multiStackParameters.buildListOfStackWithAllZ();

        final JavaRDD<StackWithZValues> rddStackWithZValues = sparkContext.parallelize(stackWithZValuesList);

        final Function<StackWithZValues, ConnectedTileClusterSummaryForStack> summarizeFunction = stackWithZ -> {

            LogUtilities.setupExecutorLog4j(stackWithZ.toString());

            final StackId stackId = stackWithZ.getStackId();
            final MatchCollectionId matchCollectionId = multiStackParameters.getMatchCollectionIdForStack(stackId);
            final RenderDataClient localDataClient = new RenderDataClient(baseDataUrl,
                                                                          stackId.getOwner(),
                                                                          stackId.getProject());
            final org.janelia.render.client.ClusterCountClient jClient = clientParameters.buildJavaClient();

            return jClient.findConnectedClustersForStack(stackWithZ,
                                                         matchCollectionId,
                                                         localDataClient,
                                                         clientParameters.tileCluster);
        };

        final JavaRDD<ConnectedTileClusterSummaryForStack> rddSummaries = rddStackWithZValues.map(summarizeFunction);
        
        final List<ConnectedTileClusterSummaryForStack> summaryList = rddSummaries.collect();

        LOG.info("findConnectedClusters: collected {} stack summaries", summaryList.size());

        for (final ConnectedTileClusterSummaryForStack summary : summaryList) {
            LOG.info("findConnectedClusters: {}", summary);
        }

        LOG.info("findConnectedClusters: exit");

        return summaryList;
    }

    private static final Logger LOG = LoggerFactory.getLogger(ClusterCountClient.class);
}

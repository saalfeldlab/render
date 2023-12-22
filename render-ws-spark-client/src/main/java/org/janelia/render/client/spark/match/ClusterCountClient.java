package org.janelia.render.client.spark.match;

import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
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
import org.janelia.render.client.spark.pipeline.AlignmentPipelineParameters;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.parameter.TileClusterParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for identifying connected tile clusters in a stack.
 *
 * @author Eric Trautman
 */
public class ClusterCountClient
        implements Serializable {

    public static class Parameters extends CommandLineParameters {
        @ParametersDelegate
        public MultiProjectParameters multiProject = new MultiProjectParameters();

        @ParametersDelegate
        public TileClusterParameters tileCluster = new TileClusterParameters();

        public org.janelia.render.client.ClusterCountClient buildJavaClient() {
            final org.janelia.render.client.ClusterCountClient.Parameters javaClientParameters =
                    new org.janelia.render.client.ClusterCountClient.Parameters();
            javaClientParameters.multiProject = multiProject;
            javaClientParameters.tileCluster = tileCluster;
            return new org.janelia.render.client.ClusterCountClient(javaClientParameters);
        }

        /** @return client specific parameters populated from specified alignment pipeline parameters. */
        public static Parameters fromPipeline(final AlignmentPipelineParameters alignmentPipelineParameters) {
            final Parameters derivedParameters = new Parameters();
            derivedParameters.multiProject = alignmentPipelineParameters.getMultiProject();
            derivedParameters.tileCluster = alignmentPipelineParameters.getTileCluster();
            return derivedParameters;
        }

    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                final ClusterCountClient client = new ClusterCountClient(parameters);
                client.run();

            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;

    public ClusterCountClient(final Parameters parameters) throws IllegalArgumentException {
        LOG.info("init: parameters={}", parameters);
        this.parameters = parameters;
    }

    public void run() throws IOException {

        final SparkConf conf = new SparkConf().setAppName("ClusterCountClient");

        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            final String sparkAppId = sparkContext.getConf().getAppId();
            LOG.info("run: appId is {}", sparkAppId);
            findConnectedClusters(sparkContext);
        }
    }

    public List<ConnectedTileClusterSummaryForStack> findConnectedClusters(final JavaSparkContext sparkContext)
            throws IOException {

        LOG.info("findConnectedClusters: entry");

        final RenderDataClient renderDataClient = parameters.multiProject.getDataClient();
        final String baseDataUrl = renderDataClient.getBaseDataUrl();

        final List<StackWithZValues> stackWithZValuesList =
                parameters.multiProject.stackIdWithZ.buildListOfStackWithAllZ(renderDataClient);

        final JavaRDD<StackWithZValues> rddStackWithZValues = sparkContext.parallelize(stackWithZValuesList);

        final Function<StackWithZValues, ConnectedTileClusterSummaryForStack> summarizeFunction = stackWithZ -> {

            LogUtilities.setupExecutorLog4j(stackWithZ.toString());

            final StackId stackId = stackWithZ.getStackId();
            final MatchCollectionId matchCollectionId = parameters.multiProject.getMatchCollectionIdForStack(stackId);
            final RenderDataClient localDataClient = new RenderDataClient(baseDataUrl,
                                                                          stackId.getOwner(),
                                                                          stackId.getProject());
            final org.janelia.render.client.ClusterCountClient jClient = parameters.buildJavaClient();

            return jClient.findConnectedClustersForStack(stackWithZ,
                                                         matchCollectionId,
                                                         localDataClient,
                                                         parameters.tileCluster);
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

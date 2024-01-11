package org.janelia.render.client.spark.match;

import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MatchCopyParameters;
import org.janelia.render.client.parameter.MultiStackParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineParameters;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for copying matches.
 *
 * @author Eric Trautman
 */
public class CopyMatchClient
        implements Serializable, AlignmentPipelineStep {

    public static class Parameters extends CommandLineParameters {
        @ParametersDelegate
        public MultiStackParameters multiStack;

        @ParametersDelegate
        public MatchCopyParameters matchCopy;

        public Parameters() {
            this(new MultiStackParameters(), new MatchCopyParameters());
        }

        public Parameters(final MultiStackParameters multiStack,
                          final MatchCopyParameters matchCopy) {
            this.multiStack = multiStack;
            this.matchCopy = matchCopy;
        }
    }

    /** Run the client with command line parameters. */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {
                final Parameters parameters = new Parameters();
                parameters.parse(args);
                parameters.matchCopy.validate();
                final CopyMatchClient client = new CopyMatchClient();
                client.createContextAndRun(parameters);
            }
        };
        clientRunner.run();
    }

    /** Empty constructor required for alignment pipeline steps. */
    public CopyMatchClient() {
    }

    /** Create a spark context and run the client with the specified parameters. */
    public void createContextAndRun(final Parameters clientParameters) throws IOException {
        final SparkConf conf = new SparkConf().setAppName(getClass().getSimpleName());
        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            LOG.info("createContextAndRun: appId is {}", sparkContext.getConf().getAppId());
            copyMatches(sparkContext, clientParameters);
        }
    }

    /** Validates the specified pipeline parameters are sufficient. */
    @Override
    public void validatePipelineParameters(final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException {
        AlignmentPipelineParameters.validateRequiredElementExists("matchCopy",
                                                                  pipelineParameters.getMatchCopy());
    }

    /** Run the client as part of an alignment pipeline. */
    public void runPipelineStep(final JavaSparkContext sparkContext,
                                final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException, IOException {
        final Parameters clientParameters = new Parameters(pipelineParameters.getMultiStack(pipelineParameters.getRawNamingGroup()),
                                                           pipelineParameters.getMatchCopy());
        copyMatches(sparkContext, clientParameters);
    }

    private void copyMatches(final JavaSparkContext sparkContext,
                             final Parameters clientParameters)
            throws IOException {

        LOG.info("copyMatches: entry, clientParameters={}", clientParameters);

        final MultiStackParameters multiStackParameters = clientParameters.multiStack;
        final String baseDataUrl = multiStackParameters.getBaseDataUrl();
        final MatchCopyParameters matchCopyParameters = clientParameters.matchCopy;
        final List<StackWithZValues> stackWithZValuesList = multiStackParameters.buildListOfStackWithBatchedZ();

        final JavaRDD<StackWithZValues> rddStackWithZValues = sparkContext.parallelize(stackWithZValuesList);

        final Function<StackWithZValues, Void> copyMatchFunction = stackWithZ -> {

            LogUtilities.setupExecutorLog4j(stackWithZ.toString());

            final StackId stackId = stackWithZ.getStackId();
            final MatchCollectionId matchCollectionId = multiStackParameters.getMatchCollectionIdForStack(stackId);

            final RenderDataClient sourceDataClient = new RenderDataClient(baseDataUrl,
                                                                           stackId.getOwner(),
                                                                           stackId.getProject());

            final RenderDataClient sourceMatchClient = sourceDataClient.buildClient(matchCollectionId.getOwner(),
                                                                                    matchCollectionId.getName());

            final RenderDataClient targetMatchClient = matchCopyParameters.buildTargetMatchClient(sourceMatchClient);

            final List<SectionData> sectionDataList = sourceDataClient.getStackSectionData(stackId.getStack(),
                                                                                           null,
                                                                                           null,
                                                                                           stackWithZ.getzValues());

            for (final SectionData sectionData : sectionDataList) {
                org.janelia.render.client.CopyMatchClient.copyMatches(sourceMatchClient,
                                                                      targetMatchClient,
                                                                      sectionData.getSectionId(),
                                                                      matchCopyParameters);
            }

            return null;
        };

        final JavaRDD<Void> rddCopyMatches = rddStackWithZValues.map(copyMatchFunction);
        rddCopyMatches.collect();

        LOG.info("copyMatches: collected rddPatch");
        LOG.info("copyMatches: exit");
    }

    private static final Logger LOG = LoggerFactory.getLogger(CopyMatchClient.class);
}

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
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for copying matches.
 *
 * @author Eric Trautman
 */
public class CopyMatchClient
        implements Serializable {

    public static class Parameters extends CommandLineParameters {
        @ParametersDelegate
        public MultiProjectParameters multiProject;

        @ParametersDelegate
        public MatchCopyParameters matchCopy;

        public Parameters() {
            this(new MultiProjectParameters(), new MatchCopyParameters());
        }

        public Parameters(final MultiProjectParameters multiProject,
                          final MatchCopyParameters matchCopy) {
            this.multiProject = multiProject;
            this.matchCopy = matchCopy;
        }

    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);
                parameters.matchCopy.validate();

                final CopyMatchClient client = new CopyMatchClient(parameters);
                client.run();

            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;

    public CopyMatchClient(final Parameters parameters) throws IllegalArgumentException {
        LOG.info("init: parameters={}", parameters);
        this.parameters = parameters;
    }

    public void run() throws IOException {

        final SparkConf conf = new SparkConf().setAppName("CopyMatchClient");

        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            final String sparkAppId = sparkContext.getConf().getAppId();
            LOG.info("run: appId is {}", sparkAppId);
            copyMatches(sparkContext);
        }
    }

    public void copyMatches(final JavaSparkContext sparkContext)
            throws IOException {

        LOG.info("copyMatches: phase {}, entry", parameters.matchCopy.matchCopyPhaseName);

        final RenderDataClient renderDataClient = parameters.multiProject.getDataClient();
        final String baseDataUrl = renderDataClient.getBaseDataUrl();

        final List<StackWithZValues> stackWithZValuesList =
                parameters.multiProject.stackIdWithZ.buildListOfStackWithBatchedZ(renderDataClient);

        final JavaRDD<StackWithZValues> rddStackWithZValues = sparkContext.parallelize(stackWithZValuesList);

        final Function<StackWithZValues, Void> copyMatchFunction = stackWithZ -> {

            LogUtilities.setupExecutorLog4j(stackWithZ.toString());

            final StackId stackId = stackWithZ.getStackId();
            final MatchCollectionId matchCollectionId = parameters.multiProject.getMatchCollectionIdForStack(stackId);

            final RenderDataClient sourceDataClient = new RenderDataClient(baseDataUrl,
                                                                           stackId.getOwner(),
                                                                           stackId.getProject());

            final RenderDataClient sourceMatchClient = sourceDataClient.buildClient(matchCollectionId.getOwner(),
                                                                                    matchCollectionId.getName());

            final RenderDataClient targetMatchClient = parameters.matchCopy.buildTargetMatchClient(sourceMatchClient);

            final List<SectionData> sectionDataList = sourceDataClient.getStackSectionData(stackId.getStack(),
                                                                                           null,
                                                                                           null,
                                                                                           stackWithZ.getzValues());

            for (final SectionData sectionData : sectionDataList) {
                org.janelia.render.client.CopyMatchClient.copyMatches(sourceMatchClient,
                                                                      targetMatchClient,
                                                                      sectionData.getSectionId(),
                                                                      parameters.matchCopy);
            }

            return null;
        };

        final JavaRDD<Void> rddCopyMatches = rddStackWithZValues.map(copyMatchFunction);
        rddCopyMatches.collect();

        LOG.info("copyMatches: phase {}, collected rddPatch", parameters.matchCopy.matchCopyPhaseName);
        LOG.info("copyMatches: phase {}, exit", parameters.matchCopy.matchCopyPhaseName);
    }

    private static final Logger LOG = LoggerFactory.getLogger(CopyMatchClient.class);
}

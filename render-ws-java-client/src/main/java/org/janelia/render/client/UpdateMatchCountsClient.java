package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MatchWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for updating (adding) match counts to a collection in the point match database.
 *
 * @author Eric Trautman
 */
public class UpdateMatchCountsClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        MatchWebServiceParameters matchClient = new MatchWebServiceParameters();

        @Parameter(
                names = "--totalNumberOfJobs",
                description = "total number of distributed update jobs being run",
                required = true)
        public Integer totalNumberOfJobs;

        @Parameter(
                names = "--jobIndex",
                description = "index (zero-based) of this job within the set of distributed jobs being run",
                required = true)
        public Integer jobIndex;
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final UpdateMatchCountsClient client = new UpdateMatchCountsClient(parameters);
                client.updateMatchCountsForJob();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final RenderDataClient matchDataClient;

    private UpdateMatchCountsClient(final Parameters parameters) {
        this.parameters = parameters;
        this.matchDataClient = new RenderDataClient(parameters.matchClient.baseDataUrl,
                                                    parameters.matchClient.owner,
                                                    parameters.matchClient.collection);
    }

    private void updateMatchCountsForJob()
            throws IOException {

        final List<String> allPGroupIds = matchDataClient.getMatchPGroupIds();
        final int totalNumberOfGroups = allPGroupIds.size();

        LOG.info("updateMatchCountsForJob: {} collection contains {} pGroup(s)",
                 parameters.matchClient.collection, totalNumberOfGroups);

        final List<String> pGroupIdsForJob;
        if (totalNumberOfGroups >= parameters.totalNumberOfJobs) {

            final int batchSize = (int) Math.ceil((double) allPGroupIds.size() / parameters.totalNumberOfJobs);
            final int fromIndex = parameters.jobIndex * batchSize;
            if (fromIndex < totalNumberOfGroups) {
                final int toIndex = Math.min(totalNumberOfGroups, (fromIndex + batchSize));
                pGroupIdsForJob = allPGroupIds.subList(fromIndex, toIndex);
            } else {
                pGroupIdsForJob = new ArrayList<>();
            }

        } else if (parameters.jobIndex < totalNumberOfGroups) {
            pGroupIdsForJob = allPGroupIds.subList(parameters.jobIndex, parameters.jobIndex + 1);
        } else {
            pGroupIdsForJob = new ArrayList<>();
        }

        LOG.info("updateMatchCountsForJob: mapped job {} of {} to {} pGroup(s): {}",
                 (parameters.jobIndex + 1), parameters.totalNumberOfJobs, pGroupIdsForJob.size(), pGroupIdsForJob);

        for (final String pGroupId : pGroupIdsForJob) {
            matchDataClient.updateMatchCountsForPGroup(pGroupId);
        }

        LOG.info("updateMatchCountsForJob: exit");
    }

    private static final Logger LOG = LoggerFactory.getLogger(UpdateMatchCountsClient.class);
}

package org.janelia.render.client;

import com.beust.jcommander.ParametersDelegate;

import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.MatchAggregator;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MatchCopyParameters;
import org.janelia.render.client.parameter.MatchWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.janelia.render.client.parameter.MatchCopyParameters.MatchAggregationScope.SAME_GROUP_ID_ONLY;

/**
 * Java client for copying matches from one collection to another.
 *
 * @author Eric Trautman
 */
public class CopyMatchClient {

    public static class Parameters extends CommandLineParameters {


        @ParametersDelegate
        MatchWebServiceParameters matchClient = new MatchWebServiceParameters();

        @ParametersDelegate
        public MatchCopyParameters matchCopy = new MatchCopyParameters();
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);
                parameters.matchCopy.validate();

                LOG.info("runClient: entry, parameters={}", parameters);

                final CopyMatchClient client = new CopyMatchClient();
                client.copyMatches(parameters);
            }
        };
        clientRunner.run();
    }

    public CopyMatchClient() {
    }

    private void copyMatches(final Parameters parameters)
            throws Exception {

        final RenderDataClient sourceMatchClient = parameters.matchClient.getDataClient();
        final RenderDataClient targetMatchClient = parameters.matchCopy.buildTargetMatchClient(sourceMatchClient);
        final List<String> pGroupIds;
        if ((parameters.matchCopy.pGroupIds == null) || (parameters.matchCopy.pGroupIds.size() == 0)) {
            pGroupIds = sourceMatchClient.getMatchPGroupIds();
        } else {
            pGroupIds = parameters.matchCopy.pGroupIds;
        }

        for (final String pGroupId : pGroupIds) {
            copyMatches(sourceMatchClient, targetMatchClient, pGroupId, parameters.matchCopy);
        }
    }

    public static void copyMatches(final RenderDataClient sourceMatchClient,
                                   final RenderDataClient targetMatchClient,
                                   final String pGroupId,
                                   final MatchCopyParameters matchCopy) throws Exception {

        final List<CanvasMatches> groupMatches =
                sourceMatchClient.getMatchesWithPGroupId(pGroupId, false);

        if (groupMatches.size() > 0) {

            if (matchCopy.removeExisting) {
                targetMatchClient.deleteMatchesWithPGroupId(pGroupId);
            }

            if (matchCopy.isAggregationRequested()) {
                final long originalMatchCount = countMatches(groupMatches);
                aggregateMatches(groupMatches, matchCopy);
                final long postAggregationMatchCount = countMatches(groupMatches);

                LOG.info("copyMatches: {} phase, {} match points were aggregated down to {} match points for group {}",
                         matchCopy.matchCopyPhaseName, originalMatchCount, postAggregationMatchCount, pGroupId);
            }

            targetMatchClient.saveMatches(groupMatches);

        } else {
            LOG.info("copyMatches: {} phase, no matches found for pGroupId {} in {}",
                     matchCopy.matchCopyPhaseName, pGroupId, sourceMatchClient.getProject());
        }
    }

    private static void aggregateMatches(List<CanvasMatches> groupMatches,
                                         final MatchCopyParameters matchCopy) {

        final List<CanvasMatches> matchesToKeepAsIs;
        final List<CanvasMatches> matchesToAggregate;

        switch (matchCopy.matchAggregationScope) {
            case SAME_GROUP_ID_ONLY:
            case DIFFERENT_GROUP_IDS_ONLY:
                matchesToKeepAsIs = new ArrayList<>(groupMatches.size());
                matchesToAggregate = new ArrayList<>(groupMatches.size());
                final boolean aggregateSameGroups = matchCopy.matchAggregationScope.equals(SAME_GROUP_ID_ONLY);

                for (final CanvasMatches pair : groupMatches) {
                    if (pair.getpGroupId().equals(pair.getqGroupId())) { // pair has same group id
                        if (aggregateSameGroups) {
                            matchesToAggregate.add(pair);
                        } else {
                            matchesToKeepAsIs.add(pair);
                        }
                    } else { // pair has different group id
                        if (aggregateSameGroups) {
                            matchesToKeepAsIs.add(pair);
                        } else {
                            matchesToAggregate.add(pair);
                        }
                    }
                }
                break;

            default:
                matchesToKeepAsIs = new ArrayList<>();
                matchesToAggregate = groupMatches;
                break;
        }

        MatchAggregator.aggregateWithinRadius(matchesToAggregate,
                                              matchCopy.maxMatchesPerPair,
                                              matchCopy.matchAggregationRadius);

        groupMatches = matchesToAggregate;

        // add non-aggregated matches back into list if only a subset of pairs was aggregated
        groupMatches.addAll(matchesToKeepAsIs);
    }

    public static long countMatches(final List<CanvasMatches> canvasMatchesList) {
        long count = 0;
        for (final CanvasMatches canvasMatches : canvasMatchesList) {
            count = count + canvasMatches.getMatchCount();
        }
        return count;
    }

    private static final Logger LOG = LoggerFactory.getLogger(CopyMatchClient.class);
}

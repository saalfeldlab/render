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

                LOG.info("copyMatches: {} match points were aggregated down to {} match points for group {}",
                         originalMatchCount, postAggregationMatchCount, pGroupId);
            }

            targetMatchClient.saveMatches(groupMatches);

        } else {
            LOG.info("copyMatches: no matches found for pGroupId {} in {}",
                     pGroupId, sourceMatchClient.getProject());
        }
    }

    private static void aggregateMatches(final List<CanvasMatches> groupMatches,
                                         final MatchCopyParameters matchCopy) {

        final List<CanvasMatches> sameLayerMatches = new ArrayList<>(groupMatches.size());
        final List<CanvasMatches> crossLayerMatches = new ArrayList<>(groupMatches.size());
        
        for (final CanvasMatches pair : groupMatches) {
            if (pair.getpGroupId().equals(pair.getqGroupId())) { // pair has same group id
                sameLayerMatches.add(pair);
            } else {
                crossLayerMatches.add(pair);
            }
        }

        if (matchCopy.isSameLayerAggregationRequested()) {
            MatchAggregator.aggregateWithinRadius(sameLayerMatches,
                                                  matchCopy.maxMatchesPerPairSame,
                                                  matchCopy.matchAggregationRadiusSame);
        }

        if (matchCopy.isCrossLayerAggregationRequested()) {
            MatchAggregator.aggregateWithinRadius(crossLayerMatches,
                                                  matchCopy.maxMatchesPerPairCross,
                                                  matchCopy.matchAggregationRadiusCross);
        }
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

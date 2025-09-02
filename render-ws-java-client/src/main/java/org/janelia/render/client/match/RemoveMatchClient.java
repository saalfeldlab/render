package org.janelia.render.client.match;

import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MatchRemovalParameters;
import org.janelia.render.client.parameter.MatchWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for removing matches from a collection.
 */
public class RemoveMatchClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        MatchWebServiceParameters matchClient = new MatchWebServiceParameters();

        @ParametersDelegate
        public MatchRemovalParameters matchRemoval = new MatchRemovalParameters();
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);
                parameters.matchRemoval.validate();

                LOG.info("runClient: entry, parameters={}", parameters);

                final RemoveMatchClient client = new RemoveMatchClient();
                client.removeMatches(parameters);
            }
        };
        clientRunner.run();
    }

    public RemoveMatchClient() {
    }

    private void removeMatches(final Parameters parameters)
            throws IOException {

        final RenderDataClient matchClient = parameters.matchClient.getDataClient();
        removeMatchPairsForCollection(matchClient, parameters.matchRemoval);
    }

    public static int removeMatchPairsForCollection(final RenderDataClient matchClient,
                                                    final MatchRemovalParameters matchRemoval)
            throws IOException {

        int removedPairCount = 0;
        final List<String> pGroupIds = matchClient.getMatchPGroupIds();
        for (final String pGroupId : pGroupIds) {
            removedPairCount += removeMatchPairsForPGroupId(matchClient, pGroupId, matchRemoval);
        }

        LOG.info("removeMatchPairsForCollection: exit, removed a total of {} match pairs in {}",
                 removedPairCount, matchClient.getProject());

        return removedPairCount;
    }

    private static int removeMatchPairsForPGroupId(final RenderDataClient matchClient,
                                                   final String pGroupId,
                                                   final MatchRemovalParameters matchRemoval)
            throws IOException {

        int removePairCount = 0;
        final List<CanvasMatches> crossLayerMatches = matchClient.getMatchesOutsideGroup(pGroupId,
                                                                                         false);

        if (! crossLayerMatches.isEmpty()) {

            final List<CanvasMatches> matchesToKeep = new ArrayList<>(crossLayerMatches.size());
            final List<CanvasMatches> matchesToDelete = new ArrayList<>(crossLayerMatches.size());

            for (final CanvasMatches match : crossLayerMatches) {
                if (match.isPOffsetFromQ(matchRemoval.minCrossMatchPixelDistance)) {
                    matchesToKeep.add(match);
                } else {
                    matchesToDelete.add(match);
                }
            }

            removePairCount = matchesToDelete.size();

            if (removePairCount > 0) {

                if (matchesToDelete.size() < 10) {
                    for (final CanvasMatches match : matchesToDelete) {
                        matchClient.deleteMatchPair(match.getpCanvasId(), match.getqCanvasId());
                    }
                } else {
                    matchClient.deleteMatchesOutsideGroup(pGroupId);
                    matchClient.saveMatches(matchesToKeep);
                }

                LOG.info("removeMatchPairsForPGroupId: removed {} out of {} cross layer pairs with pGroupId {} in {}",
                         removePairCount, crossLayerMatches.size(), pGroupId, matchClient.getProject());
            } else {
                LOG.info("removeMatchPairsForPGroupId: no cross layer pairs to remove for pGroupId {} in {}",
                         pGroupId, matchClient.getProject());
            }

        } else {
            LOG.info("removeMatchPairsForPGroupId: no matches found for pGroupId {} in {}",
                     pGroupId, matchClient.getProject());
        }

        return removePairCount;
    }

    private static final Logger LOG = LoggerFactory.getLogger(RemoveMatchClient.class);
}

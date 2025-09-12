package org.janelia.render.client.match;

import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MatchPairRemovalParameters;
import org.janelia.render.client.parameter.MatchWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for removing pairs from a match collection.
 */
public class RemoveMatchPairClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        MatchWebServiceParameters matchClient = new MatchWebServiceParameters();

        @ParametersDelegate
        public MatchPairRemovalParameters matchRemoval = new MatchPairRemovalParameters();
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);
                parameters.matchRemoval.validate();

                LOG.info("runClient: entry, parameters={}", parameters);

                final RemoveMatchPairClient client = new RemoveMatchPairClient();
                client.removeMatchPairs(parameters);
            }
        };
        clientRunner.run();
    }

    public RemoveMatchPairClient() {
    }

    private void removeMatchPairs(final Parameters parameters)
            throws IOException {

        final RenderDataClient matchClient = parameters.matchClient.getDataClient();
        removeMatchPairsForCollection(matchClient, parameters.matchRemoval);
    }

    public static List<OrderedCanvasIdPair> removeMatchPairsForCollection(final RenderDataClient matchClient,
                                                                          final MatchPairRemovalParameters matchRemoval)
            throws IOException {

        final List<OrderedCanvasIdPair> pairsToRemove = new  ArrayList<>();

        final List<String> pGroupIds = matchClient.getMatchPGroupIds();
        for (final String pGroupId : pGroupIds) {
            pairsToRemove.addAll(
                    findPairsToRemoveForPGroupId(matchClient, pGroupId, matchRemoval));
        }

        if (pairsToRemove.isEmpty()) {

            LOG.info("removeMatchPairsForCollection: no match pairs to remove from {}",
                     matchClient.getProject());

        } else if (pairsToRemove.size() <= matchRemoval.maxNumberOfPairsToRemove) {

            for (final OrderedCanvasIdPair pair : pairsToRemove) {
                matchClient.deleteMatchPair(pair.getP(), pair.getQ());
            }

        } else {

            throw new IOException("found " + pairsToRemove + " pairs to remove from " + matchClient.getProject() +
                                  " which exceeds the maximum of " + matchRemoval.maxNumberOfPairsToRemove);

        }

        return pairsToRemove;
    }

    private static List<OrderedCanvasIdPair> findPairsToRemoveForPGroupId(final RenderDataClient matchClient,
                                                                          final String pGroupId,
                                                                          final MatchPairRemovalParameters matchRemoval)
            throws IOException {

        final List<OrderedCanvasIdPair> pairsToRemove = new ArrayList<>();

        final List<CanvasMatches> crossLayerMatchPairs = matchClient.getMatchesOutsideGroup(pGroupId,
                                                                                            false);
        if (! crossLayerMatchPairs.isEmpty()) {
            for (final CanvasMatches matchPair : crossLayerMatchPairs) {
                if (! matchPair.isPOffsetFromQ(matchRemoval.minCrossMatchPixelDistance)) {
                    pairsToRemove.add(new OrderedCanvasIdPair(matchPair.getpCanvasId(),
                                                              matchPair.getqCanvasId(),
                                                              null));
                }
            }
        }

        LOG.info("findPairsToRemoveForPGroupId: found {} pair(s) to remove for pGroupId {} in {}",
                 pairsToRemove.size(), pGroupId, matchClient.getProject());

        return pairsToRemove;
    }

    private static final Logger LOG = LoggerFactory.getLogger(RemoveMatchPairClient.class);
}

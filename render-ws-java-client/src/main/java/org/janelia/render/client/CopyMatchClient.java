package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.util.List;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.render.client.parameter.CommandLineParameters;
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

        @Parameter(
                names = "--toOwner",
                description = "Name of target collection owner (default is same as source stack owner)"
        )
        public String toOwner;

        @Parameter(
                names = "--toCollection",
                description = "Name of target collection",
                required = true)
        public String toCollection;

        @Parameter(
                names = "--pGroupId",
                description = "pGroupId to be copied (omit to copy all groups)",
                variableArity = true)
        public List<String> pGroupIds;

        @Parameter(
                names = "--removeExisting",
                description = "Remove any existing target matches with the specified pGroupId (default is to keep them)",
                arity = 0)
        public boolean removeExisting = false;

        String getToOwner() {
            if (toOwner == null) {
                toOwner = matchClient.owner;
            }
            return toOwner;
        }

    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final CopyMatchClient client = new CopyMatchClient(parameters);
                client.copyMatches();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final RenderDataClient fromDataClient;
    private final RenderDataClient toDataClient;
    private final List<String> pGroupIds;

    private CopyMatchClient(final Parameters parameters) throws Exception {

        this.parameters = parameters;

        this.fromDataClient = new RenderDataClient(parameters.matchClient.baseDataUrl,
                                                   parameters.matchClient.owner,
                                                   parameters.matchClient.collection);

        this.toDataClient = new RenderDataClient(parameters.matchClient.baseDataUrl,
                                                 parameters.getToOwner(),
                                                 parameters.toCollection);

        if ((parameters.pGroupIds == null) || (parameters.pGroupIds.size() == 0)) {
            this.pGroupIds = fromDataClient.getMatchPGroupIds();
        } else {
            this.pGroupIds = parameters.pGroupIds;
        }

    }

    private void copyMatches()
            throws Exception {
        for (final String pGroupId : pGroupIds) {
            copyMatches(pGroupId);
        }
    }

    private void copyMatches(final String pGroupId) throws Exception {

        final List<CanvasMatches> sourceMatches =
                fromDataClient.getMatchesWithPGroupId(pGroupId, false);

        if (sourceMatches.size() > 0) {

            if (parameters.removeExisting) {
                toDataClient.deleteMatchesWithPGroupId(pGroupId);
            }

            toDataClient.saveMatches(sourceMatches);

        } else {
            LOG.info("no matches found for pGroupId {} in {}", pGroupId, parameters.matchClient.collection);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(CopyMatchClient.class);
}

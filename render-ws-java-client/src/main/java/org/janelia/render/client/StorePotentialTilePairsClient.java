package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.Matches;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MatchWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for storing potential tile pairs as "empty" matches in a match collection
 * to facilitate tracking all processed potential pairs.
 *
 * @author Eric Trautman
 */
public class StorePotentialTilePairsClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public MatchWebServiceParameters matchClient = new MatchWebServiceParameters();

        @Parameter(
                names = "--pairJson",
                description = "JSON file where potential tile pairs are stored (.json, .gz, or .zip)",
                required = true)
        public List<String> pairJson;

    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final StorePotentialTilePairsClient client = new StorePotentialTilePairsClient(parameters);
                client.run();

            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;
    private final RenderDataClient matchStorageClient;

    public StorePotentialTilePairsClient(final Parameters parameters) throws IllegalArgumentException {

        this.parameters = parameters;
        this.matchStorageClient = new RenderDataClient(parameters.matchClient.baseDataUrl,
                                                       parameters.matchClient.owner,
                                                       parameters.matchClient.collection);
    }

    public void run() throws IOException, URISyntaxException {
        for (final String pairJsonFileName : parameters.pairJson) {
            storeEmptyMatchesForPairFile(pairJsonFileName);
        }
    }

    private void storeEmptyMatchesForPairFile(final String pairJsonFileName)
            throws IOException, URISyntaxException {

        LOG.info("storeEmptyMatchesForPairFile: pairJsonFileName is {}", pairJsonFileName);

        final RenderableCanvasIdPairs renderableCanvasIdPairs = RenderableCanvasIdPairs.load(pairJsonFileName);
        final List<OrderedCanvasIdPair> pairList = renderableCanvasIdPairs.getNeighborPairs();
        final List<CanvasMatches> matchesList = new ArrayList<>(pairList.size());
        final Matches emptyMatches = new Matches(new double[1][0], new double[1][0], new double[0]);

        CanvasId p;
        CanvasId q;
        for (final OrderedCanvasIdPair pair : pairList) {
            p = pair.getP();
            q = pair.getQ();
            matchesList.add(new CanvasMatches(p.getGroupId(), p.getId(), q.getGroupId(), q.getId(), emptyMatches));
        }

        if (matchesList.size() > 0) {
            matchStorageClient.saveMatches(matchesList);
        }

        LOG.info("storeEmptyMatchesForPairFile: exit");
    }

    private static final Logger LOG = LoggerFactory.getLogger(StorePotentialTilePairsClient.class);
}

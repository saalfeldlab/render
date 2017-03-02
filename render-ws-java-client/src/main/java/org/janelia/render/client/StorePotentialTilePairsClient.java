package org.janelia.render.client;

import com.beust.jcommander.Parameter;

import java.io.IOException;
import java.io.Reader;
import java.net.URISyntaxException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.Matches;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for storing potential tile pairs as "empty" matches in a match collection
 * to facilitate tracking all processed potential pairs.
 *
 * @author Eric Trautman
 */
public class StorePotentialTilePairsClient {

    @SuppressWarnings("ALL")
    public static class Parameters extends MatchDataClientParameters {

        // NOTE: --baseDataUrl, --owner, and --collection parameters defined in MatchDataClientParameters

        @Parameter(names = "--pairJson", description = "JSON file where potential tile pairs are stored (.json, .gz, or .zip)", required = true)
        private List<String> pairJson;

    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args, StorePotentialTilePairsClient.class);

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
        this.matchStorageClient = new RenderDataClient(parameters.baseDataUrl,
                                                       parameters.owner,
                                                       parameters.collection);
    }

    public void run() throws IOException, URISyntaxException {
        for (final String pairJsonFileName : parameters.pairJson) {
            storeEmptyMatchesForPairFile(pairJsonFileName);
        }
    }

    private void storeEmptyMatchesForPairFile(final String pairJsonFileName)
            throws IOException, URISyntaxException {

        LOG.info("storeEmptyMatchesForPairFile: pairJsonFileName is {}", pairJsonFileName);

        final RenderableCanvasIdPairs renderableCanvasIdPairs = load(pairJsonFileName);
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

    /**
     * @return pairs object loaded from the specified file.
     */
    private static RenderableCanvasIdPairs load(final String dataFile)
            throws IOException, IllegalArgumentException {

        // TODO: this was cut-and-pasted from spark client module, but should be refactored into render-app for common use

        final RenderableCanvasIdPairs renderableCanvasIdPairs;

        final Path path = FileSystems.getDefault().getPath(dataFile).toAbsolutePath();

        LOG.info("load: entry, path={}", path);

        try (final Reader reader = FileUtil.DEFAULT_INSTANCE.getExtensionBasedReader(path.toString())) {
            renderableCanvasIdPairs = RenderableCanvasIdPairs.fromJson(reader);
        }

        LOG.info("load: exit, loaded {} pairs", renderableCanvasIdPairs.size());


        return renderableCanvasIdPairs;
    }

    private static final Logger LOG = LoggerFactory.getLogger(StorePotentialTilePairsClient.class);
}

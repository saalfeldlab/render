package org.janelia.render.client;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.util.FileUtil;
import org.janelia.alignment.util.ProcessTimer;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MatchWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

/**
 * Java client for importing JSON point match data into the point match database.
 *
 * @author Eric Trautman
 */
public class ImportMatchClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public MatchWebServiceParameters matchClient = new MatchWebServiceParameters();

        @Parameter(
                names = "--batchSize",
                description = "maximum number of matches to batch in a single request",
                required = false)
        public Integer batchSize = 10000;

        @Parameter(
                description = "list of canvas match data files, each file (.json, .gz, or .zip) can contain an arbitrary set of matches",
                required = true)
        public List<String> canvasMatchesFiles;
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final ImportMatchClient client = new ImportMatchClient(parameters);

                for (final String dataFile : parameters.canvasMatchesFiles) {
                    client.importMatchData(dataFile);
                }
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final RenderDataClient renderDataClient;

    public ImportMatchClient(final Parameters parameters)
            throws IOException {
        this.parameters = parameters;
        this.renderDataClient = new RenderDataClient(parameters.matchClient.baseDataUrl,
                                                     parameters.matchClient.owner,
                                                     parameters.matchClient.collection);
    }

    public void importMatchData(final String dataFile) throws Exception {

        LOG.info("importMatchData: entry, dataFile={}", dataFile);

        final List<CanvasMatches> canvasMatches = loadCanvasMatches(dataFile);

        if (canvasMatches.size() > 0) {


            final ProcessTimer timer = new ProcessTimer();
            int batchStop = 0;

            for (int batchStart = 0; batchStart < canvasMatches.size(); batchStart += parameters.batchSize) {

                batchStop = batchStart + parameters.batchSize;
                if (batchStop > canvasMatches.size()) {
                    batchStop = canvasMatches.size();
                }

                renderDataClient.saveMatches(canvasMatches.subList(batchStart, batchStop));

                if (timer.hasIntervalPassed()) {
                    LOG.info("importMatchData: saved {} out of {} matches",
                             batchStop, canvasMatches.size());
                }
            }

            LOG.info("importMatchData: saved {} matches, elapsedSeconds={}",
                     batchStop, timer.getElapsedSeconds());

        }

        LOG.info("importMatchData: exit, saved {} matches from {}", canvasMatches.size(), dataFile);
    }

    private List<CanvasMatches> loadCanvasMatches(final String dataFile)
            throws IOException, IllegalArgumentException {

        final List<CanvasMatches> list;

        final Path path = FileSystems.getDefault().getPath(dataFile).toAbsolutePath();

        LOG.info("loadCanvasMatches: entry, path={}", path);

        try (final Reader reader = FileUtil.DEFAULT_INSTANCE.getExtensionBasedReader(path.toString())) {
            list = CanvasMatches.fromJsonArray(reader);
        }

        LOG.info("loadCanvasMatches: exit, loaded {} canvas matches", list.size());

        return list;
    }

    private static final Logger LOG = LoggerFactory.getLogger(ImportMatchClient.class);
}

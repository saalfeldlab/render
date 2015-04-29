package org.janelia.render.client;

import com.beust.jcommander.Parameter;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.util.ProcessTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for importing JSON point match data into the point match database.
 *
 * @author Eric Trautman
 */
public class ImportMatchClient {

    @SuppressWarnings("ALL")
    private static class Parameters extends RenderDataClientParameters {

        // NOTE: --baseDataUrl, --owner, and --project parameters defined in RenderDataClientParameters

        @Parameter(names = "--batchSize", description = "maximum number of matches to batch in a single request (default 10000)", required = false)
        private Integer batchSize = 10000;

        @Parameter(description = "list of canvas match data files, each file (.json, .gz, or .zip) can contain an arbitrary set of matches", required = true)
        private List<String> canvasMatchesFiles;
    }

    public static void main(final String[] args) {
        try {
            final Parameters parameters = new Parameters();
            parameters.parse(args);

            LOG.info("main: entry, parameters={}", parameters);

            final ImportMatchClient client = new ImportMatchClient(parameters);

            for (final String dataFile : parameters.canvasMatchesFiles) {
                client.importMatchData(dataFile);
            }

        } catch (final Throwable t) {
            LOG.error("main: caught exception", t);
            System.exit(1);
        }
    }

    private final Parameters parameters;
    private final RenderDataClient renderDataClient;

    public ImportMatchClient(final Parameters parameters)
            throws IOException {
        this.parameters = parameters;
        this.renderDataClient = parameters.getClient();
    }

    public void importMatchData(String dataFile) throws Exception {

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

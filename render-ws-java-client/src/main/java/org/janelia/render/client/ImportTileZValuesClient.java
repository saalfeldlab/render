package org.janelia.render.client;

import com.beust.jcommander.Parameter;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for importing new z values for tiles that need to be split into independent sections.
 *
 * @author Eric Trautman
 */
public class ImportTileZValuesClient {

    @SuppressWarnings("ALL")
    private static class Parameters extends RenderDataClientParameters {

        // NOTE: --baseDataUrl, --owner, and --project parameters defined in RenderDataClientParameters

        @Parameter(names = "--stack", description = "Name of stack containing tile specifications", required = true)
        private String stack;

        @Parameter(names = "--dataFile", description = "File containing tileId to z value mappings", required = true)
        private String dataFile;

    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args, ImportTileZValuesClient.class);

                LOG.info("runClient: entry, parameters={}", parameters);

                final ImportTileZValuesClient client = new ImportTileZValuesClient(parameters);

                client.updateTiles();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    private final RenderDataClient renderDataClient;

    private final Map<Double, List<String>> zToTileIdMap;

    public ImportTileZValuesClient(final Parameters parameters)
            throws IOException {
        this.parameters = parameters;

        this.renderDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                     parameters.owner,
                                                     parameters.project);

        this.renderDataClient.ensureStackIsInLoadingState(parameters.stack, null);

        this.zToTileIdMap = new HashMap<>();
    }

    public void updateTiles() throws Exception {

        LOG.info("updateTiles: entry");

        loadData();

        long updatedTileCount = 0;
        for (final Double z : zToTileIdMap.keySet()) {
            renderDataClient.updateZForTiles(parameters.stack, z, zToTileIdMap.get(z));
            updatedTileCount++;
        }

        LOG.info("updateTiles: exit, updated z values for {} tiles", updatedTileCount);
    }

    // data files are expected to have format <z> <tileId>
    private void loadData()
            throws IOException, IllegalArgumentException {

        final Path path = FileSystems.getDefault().getPath(parameters.dataFile).toAbsolutePath();

        LOG.info("loadData: entry, path={}", path);

        int lineNumber = 0;

        try (final BufferedReader reader = Files.newBufferedReader(path, Charset.defaultCharset())) {

            String line;
            String[] w;
            Double z;
            List<String> tileIdList;
            while ((line = reader.readLine()) != null) {

                lineNumber++;

                w = WHITESPACE_PATTERN.split(line);

                if (w.length < 2) {

                    LOG.warn("loadData: skipping line {} because it only contains {} words", lineNumber, w.length);

                } else {

                    try {
                        z = Double.parseDouble(w[0]);
                    } catch (final NumberFormatException e) {
                        throw new IllegalArgumentException("Failed to parse z value from line " + lineNumber +
                                                           " in " + path + ".  Invalid value is '" +
                                                           w[0] + "'.", e);
                    }

                    tileIdList = zToTileIdMap.get(z);
                    if (tileIdList == null) {
                        tileIdList = new ArrayList<>();
                        zToTileIdMap.put(z, tileIdList);
                    }

                    tileIdList.add(w[1]);
                }

            }
        }

        if (zToTileIdMap.size() == 0) {
            throw new IllegalArgumentException("No tile information found in " + path + ".");
        }

        LOG.info("loadData: exit, read {} lines from {}", lineNumber, path);
    }

    private static final Logger LOG = LoggerFactory.getLogger(ImportTileZValuesClient.class);

    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
}

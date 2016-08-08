package org.janelia.render.client;

import com.beust.jcommander.Parameter;

import java.io.IOException;
import java.io.Reader;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for removing tiles.
 *
 * @author Eric Trautman
 */
public class TileRemovalClient {

    @SuppressWarnings("ALL")
    private static class Parameters extends RenderDataClientParameters {

        // NOTE: --baseDataUrl, --owner, and --project parameters defined in RenderDataClientParametersWithValidator

        @Parameter(
                names = "--stack",
                description = "Stack name",
                required = true)
        private String stack;

        @Parameter(
                names = "--tileIdJson",
                description = "JSON file containing array of tileIds to be removed (.json, .gz, or .zip)",
                required = false)
        private String tileIdJson;

        @Parameter(
                description = "tileIds_to_remove",
                required = false)
        private List<String> tileIdList;

        public void loadTileIds()
                throws IllegalStateException, IOException {

            if (tileIdList == null) {

                if (tileIdJson == null) {
                    throw new IllegalStateException(
                            "--tileIdJson file must be specified when no tileIds are specified");
                }

                final JsonUtils.Helper<String> jsonHelper = new JsonUtils.Helper<>(String.class);
                try (final Reader reader = FileUtil.DEFAULT_INSTANCE.getExtensionBasedReader(tileIdJson)) {
                    tileIdList = jsonHelper.fromJsonArray(reader);
                }

            } else if (tileIdJson != null) {
                throw new IllegalStateException(
                        "--tileIdJson file should not be specified when explicit tileIds are specified");
            }

        }
    }

    /**
     * @param  args  see {@link Parameters} for command line argument details.
     */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args, TileRemovalClient.class);

                LOG.info("runClient: entry, parameters={}", parameters);

                parameters.loadTileIds();

                final TileRemovalClient client = new TileRemovalClient(parameters);
                client.removeTiles();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final RenderDataClient renderDataClient;

    public TileRemovalClient(final Parameters parameters) {
        this.parameters = parameters;
        this.renderDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                     parameters.owner,
                                                     parameters.project);
    }

    public void removeTiles()
            throws Exception {
        for (final String tileId : parameters.tileIdList) {
            renderDataClient.deleteStackTile(parameters.stack, tileId);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(TileRemovalClient.class);
}

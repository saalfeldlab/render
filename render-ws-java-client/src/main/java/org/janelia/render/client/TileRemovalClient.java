package org.janelia.render.client;

import com.beust.jcommander.Parameter;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileBoundsRTree;
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

        @Parameter(
                names = "--hiddenTilesWithZ",
                description = "",
                required = false)
        private Double hiddenTilesWithZ;

        public void loadTileIds(final RenderDataClient renderDataClient)
                throws IllegalStateException, IOException {

            if (hiddenTilesWithZ != null) {

                if (tileIdList != null) {
                    throw new IllegalStateException(
                            "--tileIdList should not be specified when --hiddenTilesWithZ is specified");
                }

                if (tileIdJson != null) {
                    throw new IllegalStateException(
                            "--tileIdJson should not be specified when --hiddenTilesWithZ is specified");
                }

                final List<TileBounds> tileBoundsList = renderDataClient.getTileBounds(stack, hiddenTilesWithZ);
                TileBoundsRTree tree = new TileBoundsRTree(hiddenTilesWithZ, tileBoundsList);

                final List<TileBounds> hiddenTileBoundsList = tree.findCompletelyObscuredTiles();
                tileIdList = new ArrayList<>(hiddenTileBoundsList.size());

                for (final TileBounds hiddenTileBounds : hiddenTileBoundsList) {
                    tileIdList.add(hiddenTileBounds.getTileId());
                }

                LOG.info("loadTileIds: found {} hidden tile ids to remove from z {}",
                         tileIdList.size(), hiddenTilesWithZ);

            } else if (tileIdJson != null) {

                if (tileIdList != null) {
                    throw new IllegalStateException(
                            "--tileIdList should not be specified when --tileIdJson is specified");
                }

                final JsonUtils.Helper<String> jsonHelper = new JsonUtils.Helper<>(String.class);
                try (final Reader reader = FileUtil.DEFAULT_INSTANCE.getExtensionBasedReader(tileIdJson)) {
                    tileIdList = jsonHelper.fromJsonArray(reader);
                }

                LOG.info("loadTileIds: loaded {} tile ids from {}", tileIdList.size(), tileIdJson);

            } // else tileIdList was explictly specified on command line

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

                final RenderDataClient renderDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                                               parameters.owner,
                                                                               parameters.project);
                parameters.loadTileIds(renderDataClient);

                final TileRemovalClient client = new TileRemovalClient(parameters);
                client.removeTiles(renderDataClient);
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    public TileRemovalClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    public void removeTiles(final RenderDataClient renderDataClient)
            throws Exception {
        for (final String tileId : parameters.tileIdList) {
            renderDataClient.deleteStackTile(parameters.stack, tileId);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(TileRemovalClient.class);
}

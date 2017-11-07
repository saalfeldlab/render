package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileBoundsRTree;
import org.janelia.alignment.util.FileUtil;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for removing tiles.
 *
 * @author Eric Trautman
 */
public class TileRemovalClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Stack name",
                required = true)
        public String stack;

        @Parameter(
                names = "--tileIdJson",
                description = "JSON file containing array of tileIds to be removed (.json, .gz, or .zip)",
                required = false)
        public String tileIdJson;

        @Parameter(
                description = "tileIds_to_remove",
                required = false)
        public List<String> tileIdList;

        @Parameter(
                names = "--hiddenTilesWithZ",
                description = "Z value for all hidden tiles to be removed",
                required = false)
        public Double hiddenTilesWithZ;

        @Parameter(
                names = "--keepZ",
                description = "Z value for all tiles to be kept",
                required = false)
        public Double keepZ;

        @Parameter(
                names = "--keepMinX",
                description = "Minimum X value for all tiles to be kept",
                required = false)
        public Double keepMinX;

        @Parameter(
                names = "--keepMinY",
                description = "Minimum Y value for all tiles to be kept",
                required = false)
        public Double keepMinY;

        @Parameter(
                names = "--keepMaxX",
                description = "Maximum X value for all tiles to be kept",
                required = false)
        public Double keepMaxX;

        @Parameter(
                names = "--keepMaxY",
                description = "Maximum Y value for all tiles to be kept",
                required = false)
        public Double keepMaxY;

        private boolean isKeepBoxSpecified() {
            return ((keepZ != null) && (keepMinX != null) && (keepMaxX != null) &&
                    (keepMinY != null) && (keepMaxY != null));
        }

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

                if (isKeepBoxSpecified()) {
                    throw new IllegalStateException(
                            "--keep parameters should not be specified when --hiddenTilesWithZ is specified");
                }

                final List<TileBounds> tileBoundsList = renderDataClient.getTileBounds(stack, hiddenTilesWithZ);
                final TileBoundsRTree tree = new TileBoundsRTree(hiddenTilesWithZ, tileBoundsList);

                final List<TileBounds> hiddenTileBoundsList = tree.findCompletelyObscuredTiles();
                tileIdList = new ArrayList<>(hiddenTileBoundsList.size());

                for (final TileBounds hiddenTileBounds : hiddenTileBoundsList) {
                    tileIdList.add(hiddenTileBounds.getTileId());
                }

                LOG.info("loadTileIds: found {} hidden tiles to remove from z {}",
                         tileIdList.size(), hiddenTilesWithZ);

            } else if (tileIdJson != null) {

                if (tileIdList != null) {
                    throw new IllegalStateException(
                            "--tileIdList should not be specified when --tileIdJson is specified");
                }

                if (isKeepBoxSpecified()) {
                    throw new IllegalStateException(
                            "--keep parameters should not be specified when --tileIdJson is specified");
                }

                final JsonUtils.Helper<String> jsonHelper = new JsonUtils.Helper<>(String.class);
                try (final Reader reader = FileUtil.DEFAULT_INSTANCE.getExtensionBasedReader(tileIdJson)) {
                    tileIdList = jsonHelper.fromJsonArray(reader);
                }

                LOG.info("loadTileIds: loaded {} tile ids from {}", tileIdList.size(), tileIdJson);

            } else if (isKeepBoxSpecified()) {

                if (tileIdList != null) {
                    throw new IllegalStateException(
                            "--tileIdList should not be specified when --keep parameters are specified");
                }

                final List<TileBounds> tileBoundsList = renderDataClient.getTileBounds(stack, keepZ);
                final TileBoundsRTree tree = new TileBoundsRTree(keepZ, tileBoundsList);
                final Set<String> keeperTileIds = new HashSet<>(tileBoundsList.size() * 2);
                for (final TileBounds keeper : tree.findTilesInBox(keepMinX, keepMinY, keepMaxX, keepMaxY)) {
                    keeperTileIds.add(keeper.getTileId());
                }

                tileIdList = new ArrayList<>(tileBoundsList.size());
                for (final TileBounds tileBounds : tileBoundsList) {
                    if (! keeperTileIds.contains(tileBounds.getTileId())) {
                        tileIdList.add(tileBounds.getTileId());
                    }
                }

                LOG.info("loadTileIds: found {} tiles outside the box to remove from z {}",
                         tileIdList.size(), keepZ);

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
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final RenderDataClient renderDataClient = parameters.renderWeb.getDataClient();

                renderDataClient.ensureStackIsInLoadingState(parameters.stack, null);

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

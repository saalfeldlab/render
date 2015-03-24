package org.janelia.render.client;

import com.beust.jcommander.Parameter;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileCoordinates;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ProcessTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for translating world coordinates between two stacks in the same project.
 *
 * @author Eric Trautman
 */
public class CoordinateClient {

    @SuppressWarnings("ALL")
    private static class Parameters extends RenderDataClientParameters {

        // NOTE: --baseDataUrl, --owner, and --project parameters defined in RenderDataClientParameters

        @Parameter(names = "--stack", description = "Stack name", required = true)
        private String stack;

        @Parameter(names = "--z", description = "Z value for all source coordinates", required = true)
        private Double z;

        @Parameter(names = "--fromJson", description = "JSON file containing coordinates to be mapped", required = true)
        private String fromJson;

        @Parameter(names = "--toJson", description = "JSON file where mapped coordinates are to be stored", required = true)
        private String toJson;

        @Parameter(names = "--localToWorld", description = "Convert from local to world coordinates (default is to convert from world to local)", required = false, arity = 0)
        private boolean localToWorld = false;
    }

    public static void main(final String[] args) {
        try {
            final Parameters parameters = new Parameters();
            parameters.parse(args);

            final File fromFile = new File(parameters.fromJson).getAbsoluteFile();
            if (! fromFile.canRead()) {
                throw new IllegalArgumentException("cannot read " + fromFile.getAbsolutePath());
            }

            File toFile = new File(parameters.toJson).getAbsoluteFile();
            if (! toFile.exists()) {
                toFile = toFile.getParentFile();
            }
            if (! toFile.canWrite()) {
                throw new IllegalArgumentException("cannot write " + toFile.getAbsolutePath());
            }

            LOG.info("main: entry, parameters={}", parameters);

            final CoordinateClient client = new CoordinateClient(parameters.stack,
                                                                 parameters.z,
                                                                 parameters.getClient());

            final Object coordinatesToSave;
            if (parameters.localToWorld) {
                final List<List<TileCoordinates>> loadedLocalCoordinates =
                        loadJsonArrayOfArraysOfCoordinates(parameters.fromJson);
                coordinatesToSave = client.localToWorld(loadedLocalCoordinates);
            } else {
                final List<TileCoordinates> loadedWorldCoordinates =
                        loadJsonArrayOfCoordinates(parameters.fromJson);
                final List<List<TileCoordinates>> worldCoordinatesWithTileIds =
                        client.getWorldCoordinatesWithTileIds(loadedWorldCoordinates);
                coordinatesToSave = client.worldToLocal(worldCoordinatesWithTileIds);
            }

            saveJsonFile(parameters.toJson, coordinatesToSave);

        } catch (final Throwable t) {
            LOG.error("main: caught exception", t);
            System.exit(1);
        }
    }

    private final String stack;
    private final Double z;
    private final RenderDataClient renderDataClient;

    public CoordinateClient(final String stack,
                            final Double z,
                            final RenderDataClient renderDataClient) {
        this.stack = stack;
        this.z = z;
        this.renderDataClient = renderDataClient;
    }

    @Override
    public String toString() {
        return "CoordinateClient{" +
               "renderDataClient=" + renderDataClient +
               ", stack='" + stack + '\'' +
               ", z='" + z + '\'' +
               '}';
    }

    public List<List<TileCoordinates>> getWorldCoordinatesWithTileIds(final List<TileCoordinates> worldList)
            throws IOException, IllegalStateException {

        LOG.info("getWorldCoordinatesWithTileIds: entry, worldList.size()={}", worldList.size());

        final List<List<TileCoordinates>> worldListOfLists =
                renderDataClient.getTileIdsForCoordinates(worldList, stack, z);

        if (worldList.size() != worldListOfLists.size()) {
            throw new IllegalStateException("mapped " + worldList.size() + " coordinates to " +
                                            worldListOfLists.size() +
                                            " coordinate lists with tileIds but counts should be the same");
        }

        LOG.info("getWorldCoordinatesWithTileIds: returning {} coordinates with tileIds", worldListOfLists.size());

        return worldListOfLists;
    }

    public List<List<TileCoordinates>> worldToLocal(final List<List<TileCoordinates>> worldListOfLists)
            throws IOException {
        return worldToLocal(worldListOfLists, getResolvedTiles());
    }

    public List<List<TileCoordinates>> worldToLocal(final List<List<TileCoordinates>> worldListOfLists,
                                                    final ResolvedTileSpecCollection tiles)
            throws IOException {

        LOG.info("worldToLocal: entry, worldListOfLists.size()={}",
                 worldListOfLists.size());

        final ProcessTimer timer = new ProcessTimer();

        final List<List<TileCoordinates>> localListOfLists = new ArrayList<>(worldListOfLists.size());

        List<TileSpec> tileSpecList;
        List<TileCoordinates> coordinatesList;
        TileCoordinates coordinates = null;
        double[] world;
        int errorCount = 0;
        for (int i = 0; i < worldListOfLists.size(); i++) {

            coordinatesList = worldListOfLists.get(i);
            try {

                tileSpecList = getTileSpecsForCoordinates(coordinatesList, tiles);

                coordinates = coordinatesList.get(0);
                world = coordinates.getWorld();
                if (world == null) {
                    throw new IllegalArgumentException("world values are missing");
                } else if (world.length < 2) {
                    throw new IllegalArgumentException("world values must include both x and y");
                }

                localListOfLists.add(TileCoordinates.getLocalCoordinates(tileSpecList,
                                                                         world[0],
                                                                         world[1]));

            } catch (final Throwable t) {

                LOG.warn("worldToLocal: caught exception for list item {}, adding original coordinates with error message to list", i, t);

                errorCount++;

                if (coordinates == null) {
                    coordinates = TileCoordinates.buildWorldInstance(null, null);
                }
                coordinates.setError(t.getMessage());

                localListOfLists.add(Arrays.asList(coordinates));
            }

            if (timer.hasIntervalPassed()) {
                LOG.info("worldToLocal: inversely transformed {} out of {} points",
                         localListOfLists.size(), worldListOfLists.size());
            }

        }

        LOG.info("worldToLocal: inversely transformed {} points with {} errors in {} seconds",
                 localListOfLists.size(), errorCount, timer.getElapsedSeconds());

        return localListOfLists;
    }

    public List<TileCoordinates> localToWorld(final List<List<TileCoordinates>> localCoordinatesList)
            throws IOException {
        return localToWorld(localCoordinatesList, getResolvedTiles());
    }

    public List<TileCoordinates> localToWorld(final List<List<TileCoordinates>> localCoordinatesListOfLists,
                                              final ResolvedTileSpecCollection tiles)
            throws IOException {

        LOG.info("localToWorld: localCoordinatesList.size()={}", localCoordinatesListOfLists.size());

        final ProcessTimer timer = new ProcessTimer();

        final List<TileCoordinates> worldCoordinatesList = new ArrayList<>(localCoordinatesListOfLists.size());
        TileSpec tileSpec;
        TileCoordinates coordinates;
        String tileId;
        double[] local;
        int errorCount = 0;
        for (int i = 0; i < localCoordinatesListOfLists.size(); i++) {

            coordinates = getVisibleCoordinates(localCoordinatesListOfLists.get(i));

            try {

                if (coordinates == null) {
                    throw new IllegalArgumentException("coordinates are missing");
                }

                tileId = coordinates.getTileId();
                if (tileId == null) {
                    throw new IllegalArgumentException("tileId is missing");
                }

                local = coordinates.getLocal();
                if (local == null) {
                    throw new IllegalArgumentException("local values are missing");
                } else if (local.length < 2) {
                    throw new IllegalArgumentException("local values must include both x and y");
                }

                tileSpec = tiles.getTileSpec(tileId);

                if (tileSpec == null) {
                    throw new IllegalArgumentException("tileId " + tileId + " cannot be found in layer " + z +
                                                       " of stack " + stack);
                }

                worldCoordinatesList.add(TileCoordinates.getWorldCoordinates(tileSpec, local[0], local[1]));

            } catch (final Throwable t) {

                LOG.warn("localToWorld: caught exception for list item {}, adding original coordinates with error message to list", i, t);

                errorCount++;

                if (coordinates == null) {
                    coordinates = TileCoordinates.buildLocalInstance(null, null);
                }
                coordinates.setError(t.getMessage());

                worldCoordinatesList.add(coordinates);
            }

            if (timer.hasIntervalPassed()) {
                LOG.info("localToWorld: transformed {} out of {} points",
                         worldCoordinatesList.size(), localCoordinatesListOfLists.size());
            }

        }

        LOG.info("localToWorld: exit, transformed {} points with {} errors in {} seconds",
                 worldCoordinatesList.size(), errorCount, timer.getElapsedSeconds());

        return worldCoordinatesList;
    }

    /**
     * @return the first visible coordinates in the specified list or
     *         simply the first coordinates if none are marked as visible.
     */
    private TileCoordinates getVisibleCoordinates(List<TileCoordinates> mappedCoordinatesList) {
        TileCoordinates tileCoordinates = null;
        if (mappedCoordinatesList.size() > 0) {
            tileCoordinates = mappedCoordinatesList.get(0);
            for (TileCoordinates mappedCoordinates : mappedCoordinatesList) {
                if (mappedCoordinates.isVisible()) {
                    tileCoordinates = mappedCoordinates;
                    break;
                }
            }
        }
        return tileCoordinates;
    }

    private ResolvedTileSpecCollection getResolvedTiles()
            throws IOException {
        final ResolvedTileSpecCollection tiles = renderDataClient.getResolvedTiles(stack, z);
        tiles.resolveTileSpecs();
        return tiles;
    }

    private List<TileSpec> getTileSpecsForCoordinates(final List<TileCoordinates> coordinatesList,
                                                      final ResolvedTileSpecCollection tiles) {

        if ((coordinatesList == null) || (coordinatesList.size() == 0)) {
            throw new IllegalArgumentException("coordinates are missing");
        }

        String tileId;
        TileSpec tileSpec;
        final List<TileSpec> tileSpecList = new ArrayList<>();
        for (final TileCoordinates coordinates : coordinatesList) {
            tileId = coordinates.getTileId();
            if (tileId != null) {
                tileSpec = tiles.getTileSpec(tileId);
                if (tileSpec != null) {
                    tileSpecList.add(tileSpec);
                }
            }
        }

        if (tileSpecList.size() == 0) {
            throw new IllegalArgumentException("no tile specifications found in layer " + z + " of stack " + stack +
                                               " for " + coordinatesList.get(0));
        }

        return tileSpecList;
    }


    private static List<TileCoordinates> loadJsonArrayOfCoordinates(final String path)
            throws IOException {

        List<TileCoordinates> parsedFromJson;

        LOG.info("loadJsonArrayOfCoordinates: entry");

        final Path fromPath = Paths.get(path).toAbsolutePath();

        try (final Reader reader = FileUtil.DEFAULT_INSTANCE.getExtensionBasedReader(fromPath.toString())) {
            parsedFromJson = TileCoordinates.fromJsonArray(reader);
        }

        LOG.info("loadJsonArrayOfCoordinates: parsed {} coordinates from {}", parsedFromJson.size(), fromPath);

        return parsedFromJson;
    }

    public static List<List<TileCoordinates>> loadJsonArrayOfArraysOfCoordinates(final String path)
            throws IOException {

        List<List<TileCoordinates>> parsedFromJson;

        LOG.info("loadJsonArrayOfArraysOfCoordinates: entry");

        final Path fromPath = Paths.get(path).toAbsolutePath();

        try (final Reader reader = FileUtil.DEFAULT_INSTANCE.getExtensionBasedReader(fromPath.toString())) {
            parsedFromJson = TileCoordinates.fromJsonArrayOfArrays(reader);
        }

        LOG.info("loadJsonArrayOfArraysOfCoordinates: parsed {} coordinates from {}", parsedFromJson.size(), fromPath);

        return parsedFromJson;
    }

    private static void saveJsonFile(final String path,
                                     final Object coordinateData)
            throws IOException {

        final Path toPath = Paths.get(path).toAbsolutePath();

        LOG.info("saveJsonFile: entry");

        try (final Writer writer = FileUtil.DEFAULT_INSTANCE.getExtensionBasedWriter(toPath.toString())) {
            JsonUtils.GSON.toJson(coordinateData, writer);
        }

        LOG.info("saveJsonFile: exit, wrote coordinate data to {}", toPath);
    }

    private static final Logger LOG = LoggerFactory.getLogger(CoordinateClient.class);
}

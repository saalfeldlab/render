package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

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

    public static void main(String[] args) {
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
                coordinatesToSave =
                        client.localToWorld(loadCoordinatesFromJsonFile(parameters.fromJson));
            } else {
                coordinatesToSave =
                        client.worldToLocal(client.getWorldCoordinatesWithTileIds(
                                loadCoordinatesFromJsonFile(parameters.fromJson)));
            }

            saveJsonFile(parameters.toJson, coordinatesToSave);

        } catch (final Throwable t) {
            LOG.error("main: caught exception", t);
        }
    }

    private final String stack;
    private final Double z;
    private final RenderDataClient renderDataClient;

    public CoordinateClient(String stack,
                            Double z,
                            RenderDataClient renderDataClient) {
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

        LOG.info("worldToLocal: entry, worldListOfLists.size()={}",
                 worldListOfLists.size());

        final ResolvedTileSpecCollection tiles = renderDataClient.getResolvedTiles(stack, z);

        final ProcessTimer timer = new ProcessTimer();

        final List<List<TileCoordinates>> localListOfLists = new ArrayList<>(worldListOfLists.size());

        List<TileSpec> tileSpecList;
        List<TileCoordinates> coordinatesList;
        TileCoordinates coordinates = null;
        float[] world;
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

            } catch (Throwable t) {

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

    public List<TileCoordinates> localToWorld(List<TileCoordinates> localCoordinatesList)
            throws IOException {

        LOG.info("localToWorld: localCoordinatesList.size()={}", localCoordinatesList.size());

        final ResolvedTileSpecCollection tiles = renderDataClient.getResolvedTiles(stack, z);

        final ProcessTimer timer = new ProcessTimer();

        List<TileCoordinates> worldCoordinatesList = new ArrayList<>(localCoordinatesList.size());
        TileSpec tileSpec;
        TileCoordinates coordinates;
        String tileId;
        float[] local;
        int errorCount = 0;
        for (int i = 0; i < localCoordinatesList.size(); i++) {

            coordinates = localCoordinatesList.get(i);
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

            } catch (Throwable t) {

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
                         worldCoordinatesList.size(), localCoordinatesList.size());
            }

        }

        LOG.info("localToWorld: exit, transformed {} points with {} errors in {} seconds",
                 worldCoordinatesList.size(), errorCount, timer.getElapsedSeconds());

        return worldCoordinatesList;
    }

    private List<TileSpec> getTileSpecsForCoordinates(List<TileCoordinates> coordinatesList,
                                                      ResolvedTileSpecCollection tiles) {

        if ((coordinatesList == null) || (coordinatesList.size() == 0)) {
            throw new IllegalArgumentException("coordinates are missing");
        }

        String tileId;
        TileSpec tileSpec;
        List<TileSpec> tileSpecList = new ArrayList<>();
        for (TileCoordinates coordinates : coordinatesList) {
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


    private static List<TileCoordinates> loadCoordinatesFromJsonFile(final String path)
            throws IOException {

        List<TileCoordinates> parsedFromJson;

        LOG.info("loadCoordinatesFromJsonFile: entry");

        final Path fromPath = Paths.get(path).toAbsolutePath();

        InputStreamReader reader = null;
        try {
            final InputStream inputStream;
            if (path.endsWith(".gz")) {
                inputStream = new GZIPInputStream(new FileInputStream(path));
            } else {
                inputStream = new BufferedInputStream(new FileInputStream(path), 65536);
            }

            reader = new InputStreamReader(inputStream);

            final Type typeOfT = new TypeToken<List<TileCoordinates>>(){}.getType();
            parsedFromJson = JsonUtils.GSON.fromJson(reader, typeOfT);
        } finally {
            close(fromPath.toString(), reader);
        }

        LOG.info("loadCoordinatesFromJsonFile: parsed {} coordinates from {}", parsedFromJson.size(), fromPath);

        return parsedFromJson;
    }

    private static void saveJsonFile(final String path,
                                     final Object coordinateData)
            throws IOException {

        final Path toPath = Paths.get(path).toAbsolutePath();

        LOG.info("saveJsonFile: entry");

        Writer writer = null;
        try {
            final OutputStream outputStream;
            if (path.endsWith(".gz")) {
                outputStream = new GZIPOutputStream(new FileOutputStream(path));
            } else {
                outputStream = new FileOutputStream(path);
            }

            writer = new OutputStreamWriter(outputStream);

            JsonUtils.GSON.toJson(coordinateData, writer);

        } finally {
            close(toPath.toString(), writer);
        }

        LOG.info("saveJsonFile: exit, wrote coordinate data to {}", toPath);
    }

    private static void close(String context,
                              Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                LOG.warn("failed to close {}, ignoring error", context, e);
            }
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(CoordinateClient.class);
}

package org.janelia.render.client;

import com.beust.jcommander.Parameter;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileCoordinates;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ProcessTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for translating coordinates in a stack from world-to-local or local-to-world.
 * Traces can be mapped between two stacks in the same project (with the same source tiles)
 * by piping the world-to-local results from one stack into the local-to-world mapping
 * for the other stack.
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

        @Parameter(names = "--fromJson", description = "JSON file containing coordinates to be mapped (.json, .gz, or .zip)", required = true)
        private String fromJson;

        @Parameter(names = "--toJson", description = "JSON file where mapped coordinates are to be stored (.json, .gz, or .zip)", required = true)
        private String toJson;

        @Parameter(names = "--localToWorld", description = "Convert from local to world coordinates (default is to convert from world to local)", required = false, arity = 0)
        private boolean localToWorld = false;

        @Parameter(names = "--numberOfThreads", description = "Number of threads to use for conversion (default is 1)", required = false)
        private int numberOfThreads = 1;
    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args, CoordinateClient.class);

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

                LOG.info("runClient: entry, parameters={}", parameters);

                final RenderDataClient renderDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                                               parameters.owner,
                                                                               parameters.project);

                final CoordinateClient client = new CoordinateClient(parameters.stack,
                                                                     parameters.z,
                                                                     renderDataClient,
                                                                     parameters.numberOfThreads);
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

            }
        };
        clientRunner.run();

    }

    private final String stack;
    private final Double z;
    private final RenderDataClient renderDataClient;
    private final int numberOfThreads;

    public CoordinateClient(final String stack,
                            final Double z,
                            final RenderDataClient renderDataClient,
                            final int numberOfThreads) {
        this.stack = stack;
        this.z = z;
        this.renderDataClient = renderDataClient;
        this.numberOfThreads = numberOfThreads;
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
            throws IOException, InterruptedException {
        return worldToLocal(worldListOfLists, getResolvedTiles());
    }

    public List<List<TileCoordinates>> worldToLocal(final List<List<TileCoordinates>> worldListOfLists,
                                                    final ResolvedTileSpecCollection tiles)
            throws IOException, InterruptedException {

        final List<List<TileCoordinates>> localListOfLists;

        if (numberOfThreads > 1) {

            localListOfLists = new ArrayList<>(worldListOfLists.size());
            final List<Integer> batchIndexes = getBatchIndexes(numberOfThreads, worldListOfLists.size());
            final List<WorldToLocalMapper> mapperList = new ArrayList<>(numberOfThreads);

            LOG.info("worldToLocal: mapping {} coordinate lists using {} threads",
                     worldListOfLists.size(), batchIndexes.size() - 1);

            for (int i = 1; i < batchIndexes.size(); i++) {
                final WorldToLocalMapper mapper = new WorldToLocalMapper(stack,
                                                                         z,
                                                                         tiles,
                                                                         worldListOfLists,
                                                                         batchIndexes.get(i-1),
                                                                         batchIndexes.get(i));
                mapperList.add(mapper);
                mapper.start();
            }

            for (final WorldToLocalMapper mapper : mapperList) {
                LOG.info("worldToLocal: waiting for {} to finish ...", mapper);
                mapper.join();
                localListOfLists.addAll(mapper.getLocalListOfLists());
            }

        } else {

            LOG.info("worldToLocal: entry, mapping {} coordinate lists on main thread",
                     worldListOfLists.size());

            final WorldToLocalMapper mapper = new WorldToLocalMapper(stack,
                                                                     z,
                                                                     tiles,
                                                                     worldListOfLists,
                                                                     0,
                                                                     worldListOfLists.size());
            mapper.run();
            localListOfLists = mapper.getLocalListOfLists();
        }

        LOG.info("worldToLocal: exit, returning {} lists of local coordinates", localListOfLists.size());

        return localListOfLists;
    }

    public List<TileCoordinates> localToWorld(final List<List<TileCoordinates>> localCoordinatesList)
            throws IOException, InterruptedException {
        return localToWorld(localCoordinatesList, getResolvedTiles());
    }

    public List<TileCoordinates> localToWorld(final List<List<TileCoordinates>> localListOfLists,
                                              final ResolvedTileSpecCollection tiles)
            throws IOException, InterruptedException {

        final List<TileCoordinates> worldList;

        if (numberOfThreads > 1) {

            worldList = new ArrayList<>(localListOfLists.size());
            final List<Integer> batchIndexes = getBatchIndexes(numberOfThreads, localListOfLists.size());
            final List<LocalToWorldMapper> mapperList = new ArrayList<>(numberOfThreads);

            LOG.info("localToWorld: mapping {} coordinate lists using {} threads",
                     localListOfLists.size(), batchIndexes.size() - 1);

            for (int i = 1; i < batchIndexes.size(); i++) {
                final LocalToWorldMapper mapper = new LocalToWorldMapper(stack,
                                                                         z,
                                                                         tiles,
                                                                         localListOfLists,
                                                                         batchIndexes.get(i-1),
                                                                         batchIndexes.get(i));
                mapperList.add(mapper);
                mapper.start();
            }

            for (final LocalToWorldMapper mapper : mapperList) {
                LOG.info("localToWorld: waiting for {} to finish ...", mapper);
                mapper.join();
                worldList.addAll(mapper.getWorldList());
            }

        } else {

            LOG.info("localToWorld: entry, mapping {} coordinate lists on main thread",
                     localListOfLists.size());

            final LocalToWorldMapper mapper = new LocalToWorldMapper(stack,
                                                                     z,
                                                                     tiles,
                                                                     localListOfLists,
                                                                     0,
                                                                     localListOfLists.size());
            mapper.run();
            worldList = mapper.getWorldList();
        }

        LOG.info("localToWorld: exit, returning {} world coordinates", worldList.size());

        return worldList;
    }

    private ResolvedTileSpecCollection getResolvedTiles()
            throws IOException {
        final ResolvedTileSpecCollection tiles = renderDataClient.getResolvedTiles(stack, z);
        tiles.resolveTileSpecs();
        return tiles;
    }

    public static List<TileCoordinates> loadJsonArrayOfCoordinates(final String path)
            throws IOException {

        final List<TileCoordinates> parsedFromJson;

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

        final List<List<TileCoordinates>> parsedFromJson;

        LOG.info("loadJsonArrayOfArraysOfCoordinates: entry");

        final Path fromPath = Paths.get(path).toAbsolutePath();

        try (final Reader reader = FileUtil.DEFAULT_INSTANCE.getExtensionBasedReader(fromPath.toString())) {
            parsedFromJson = TileCoordinates.fromJsonArrayOfArrays(reader);
        }

        LOG.info("loadJsonArrayOfArraysOfCoordinates: parsed {} coordinates from {}", parsedFromJson.size(), fromPath);

        return parsedFromJson;
    }

    public static void saveJsonFile(final String path,
                                    final Object coordinateData)
            throws IOException {

        final Path toPath = Paths.get(path).toAbsolutePath();

        LOG.info("saveJsonFile: entry");

        try (final Writer writer = FileUtil.DEFAULT_INSTANCE.getExtensionBasedWriter(toPath.toString())) {
            JsonUtils.MAPPER.writeValue(writer, coordinateData);
        }

        LOG.info("saveJsonFile: exit, wrote coordinate data to {}", toPath);
    }

    public static List<Integer> getBatchIndexes(final int numberOfThreads,
                                                final int size) {

        final List<Integer> batchIndexes = new ArrayList<>();

        int batchSize = size / numberOfThreads;
        if ((size % numberOfThreads) > 0) {
            batchSize = batchSize + 1;
        }

        for (int i = 0; i < size; i += batchSize) {
            batchIndexes.add(i);
        }
        batchIndexes.add(size);

        return batchIndexes;
    }

    /**
     * Maps sub-list of world coordinates to local coordinates.
     */
    private static class WorldToLocalMapper extends Thread {

        private final String stack;
        private final Double z;
        private final ResolvedTileSpecCollection tiles;
        private final List<List<TileCoordinates>> worldListOfLists;
        private final List<List<TileCoordinates>> localListOfLists;
        private final int startIndex;
        private final int stopIndex;
        private int errorCount;

        public WorldToLocalMapper(final String stack,
                                  final Double z,
                                  final ResolvedTileSpecCollection tiles,
                                  final List<List<TileCoordinates>> worldListOfLists,
                                  final int startIndex,
                                  final int stopIndex) {
            this.stack = stack;
            this.z = z;
            this.tiles = tiles;
            this.worldListOfLists = worldListOfLists;
            this.startIndex = startIndex;
            this.stopIndex = stopIndex;

            this.localListOfLists = new ArrayList<>(worldListOfLists.size());
            this.errorCount = 0;
        }

        public int numberOfPoints() {
            return stopIndex - startIndex;
        }

        public List<List<TileCoordinates>> getLocalListOfLists() {
            return localListOfLists;
        }

        @Override
        public String toString() {
            return "WorldToLocalMapper[" + startIndex + "," + stopIndex + "]";
        }

        @Override
        public void run() {

            final ProcessTimer timer = new ProcessTimer();

            List<TileCoordinates> coordinatesList;
            TileCoordinates coordinates = null;

            for (int i = startIndex; (i < stopIndex) && (i < worldListOfLists.size()); i++) {

                coordinatesList = worldListOfLists.get(i);

                try {

                    final List<TileSpec> tileSpecList = getTileSpecsForCoordinates(coordinatesList, tiles);

                    coordinates = coordinatesList.get(0);
                    final double[] world = coordinates.getWorld();
                    if (world == null) {
                        throw new IllegalArgumentException("world values are missing");
                    } else if (world.length < 2) {
                        throw new IllegalArgumentException("world values must include both x and y");
                    }

                    localListOfLists.add(TileCoordinates.getLocalCoordinates(tileSpecList,
                                                                             world[0],
                                                                             world[1]));

                } catch (final Throwable t) {

                    LOG.warn("worldToLocal run: caught exception for list item {}, " +
                             "adding original coordinates with error message to list", (i + startIndex), t);

                    errorCount++;

                    if (coordinates == null) {
                        coordinates = TileCoordinates.buildWorldInstance(null, null);
                    }
                    coordinates.setError(t.getMessage());

                    localListOfLists.add(Collections.singletonList(coordinates));
                }

                if (timer.hasIntervalPassed()) {
                    LOG.info("{}: inversely transformed {} out of {} points",
                             this, (i - startIndex + 1), numberOfPoints());
                }

            }

            LOG.info("{}: exit, inversely transformed {} points with {} errors in {} seconds",
                     this, numberOfPoints(), errorCount, timer.getElapsedSeconds());

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
    }

    /**
     * Maps sub-list of local coordinates to world coordinates.
     */
    private static class LocalToWorldMapper extends Thread {

        private final String stack;
        private final Double z;
        private final ResolvedTileSpecCollection tiles;
        private final List<List<TileCoordinates>> localListOfLists;
        private final List<TileCoordinates> worldList;
        private final int startIndex;
        private final int stopIndex;
        private int errorCount;

        public LocalToWorldMapper(final String stack,
                                  final Double z,
                                  final ResolvedTileSpecCollection tiles,
                                  final List<List<TileCoordinates>> localListOfLists,
                                  final int startIndex,
                                  final int stopIndex) {
            this.stack = stack;
            this.z = z;
            this.tiles = tiles;
            this.localListOfLists = localListOfLists;
            this.startIndex = startIndex;
            this.stopIndex = stopIndex;

            this.worldList = new ArrayList<>(localListOfLists.size());
            this.errorCount = 0;
        }

        public int numberOfPoints() {
            return stopIndex - startIndex + 1;
        }

        public List<TileCoordinates> getWorldList() {
            return worldList;
        }

        @Override
        public String toString() {
            return "LocalToWorldMapper[" + startIndex + "," + stopIndex + "]";
        }

        @Override
        public void run() {

            final ProcessTimer timer = new ProcessTimer();

            TileSpec tileSpec;
            TileCoordinates coordinates;
            String tileId;
            double[] local;
            for (int i = startIndex; (i < stopIndex) && (i < localListOfLists.size()); i++) {

                coordinates = getVisibleCoordinates(localListOfLists.get(i));

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

                    worldList.add(TileCoordinates.getWorldCoordinates(tileSpec, local[0], local[1]));

                } catch (final Throwable t) {

                    LOG.warn("{}: caught exception for list item {}, " +
                             "adding original coordinates with error message to list", this, (i + startIndex), t);

                    errorCount++;

                    if (coordinates == null) {
                        coordinates = TileCoordinates.buildLocalInstance(null, null);
                    }
                    coordinates.setError(t.getMessage());

                    worldList.add(coordinates);
                }

                if (timer.hasIntervalPassed()) {
                    LOG.info("{}: transformed {} out of {} points",
                             this, (i - startIndex + 1), numberOfPoints());
                }

            }

            LOG.info("{}: exit, transformed {} points with {} errors in {} seconds",
                     this, numberOfPoints(), errorCount, timer.getElapsedSeconds());
        }

        /**
         * @return the first visible coordinates in the specified list or simply the first coordinates if none are
         * marked as visible.
         */
        private TileCoordinates getVisibleCoordinates(final List<TileCoordinates> mappedCoordinatesList) {
            TileCoordinates tileCoordinates = null;
            if (mappedCoordinatesList.size() > 0) {
                tileCoordinates = mappedCoordinatesList.get(0);
                for (final TileCoordinates mappedCoordinates : mappedCoordinatesList) {
                    if (mappedCoordinates.isVisible()) {
                        tileCoordinates = mappedCoordinates;
                        break;
                    }
                }
            }
            return tileCoordinates;
        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(CoordinateClient.class);
}

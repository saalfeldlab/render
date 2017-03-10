package org.janelia.render.client;

import com.beust.jcommander.Parameter;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileCoordinates;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackVersion;
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

        @Parameter(names = "--z", description = "Z value for all source coordinates", required = false)
        private Double z;

        @Parameter(
                names = "--toOwner",
                description = "Name of target stack owner (for round trip mapping, default is source owner)",
                required = false)
        private String toOwner;

        @Parameter(
                names = "--toProject",
                description = "Name of target stack project (for round trip mapping, default is source owner)",
                required = false)
        private String toProject;

        @Parameter(
                names = "--toStack",
                description = "Name of target stack (for round trip mapping, omit for one way mapping)",
                required = false)
        private String toStack;

        @Parameter(names = "--fromSwcDirectory", description = "directory containing source .swc files", required = false)
        private String fromSwcDirectory;

        @Parameter(names = "--toSwcDirectory", description = "directory to write target .swc files with mapped coordinates", required = false)
        private String toSwcDirectory;

        @Parameter(names = "--fromJson", description = "JSON file containing coordinates to be mapped (.json, .gz, or .zip)", required = false)
        private String fromJson;

        @Parameter(names = "--toJson", description = "JSON file where mapped coordinates are to be stored (.json, .gz, or .zip)", required = false)
        private String toJson;

        @Parameter(names = "--localToWorld", description = "Convert from local to world coordinates (default is to convert from world to local)", required = false, arity = 0)
        private boolean localToWorld = false;

        @Parameter(names = "--numberOfThreads", description = "Number of threads to use for conversion", required = false)
        private int numberOfThreads = 1;

        public String getToOwner() {
            if (toOwner == null) {
                toOwner = owner;
            }
            return toOwner;
        }

        public String getToProject() {
            if (toProject == null) {
                toProject = project;
            }
            return toProject;
        }

        public void validateInputAndOutput() throws IllegalArgumentException {

            File file;

            if (fromJson == null) {

                if (localToWorld) {
                    throw new IllegalArgumentException("--localToWorld mapping requires --fromJson to be specified");
                }

                if (fromSwcDirectory == null) {
                    throw new IllegalArgumentException("must specify input location with either --fromJson or --fromSwcDirectory");
                } else if (toSwcDirectory == null) {
                    throw new IllegalArgumentException("must specify output location with --toSwcDirectory");
                }

                file = new File(fromSwcDirectory);
                if (! file.isDirectory() || ! file.canRead()) {
                    throw new IllegalArgumentException("--fromSwcDirectory " + file.getAbsolutePath() + " must be a readable directory");
                }

                file = new File(toSwcDirectory);
                if (! file.exists()) {
                    if (! file.mkdirs()) {
                        throw new IllegalArgumentException("failed to create toSwcDirectory " + file.getAbsolutePath());
                    }
                } else if (! file.isDirectory() || ! file.canWrite()) {
                    throw new IllegalArgumentException("--toSwcDirectory " + file.getAbsolutePath() + " must be a writable directory");
                }

                if (toStack == null) {
                    throw new IllegalArgumentException("--toStack must be specified for SWC mapping");
                }

            } else if (toJson == null) {

                throw new IllegalArgumentException("must specify output location with --toJson");

            } else {

                file = new File(fromJson).getAbsoluteFile();
                if (! file.canRead()) {
                    throw new IllegalArgumentException("--fromJson " + file.getAbsolutePath() + " must be readable");
                }

                file = new File(toJson).getAbsoluteFile();
                if (! file.exists()) {
                    file = file.getParentFile();
                }
                if (! file.canWrite()) {
                    throw new IllegalArgumentException("--toJson " + file.getAbsolutePath() + " must be writeable");
                }

            }

        }

    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args, CoordinateClient.class);
                parameters.validateInputAndOutput();

                LOG.info("runClient: entry, parameters={}", parameters);

                final RenderDataClient renderDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                                               parameters.owner,
                                                                               parameters.project);

                final CoordinateClient client = new CoordinateClient(parameters.stack,
                                                                     parameters.z,
                                                                     renderDataClient,
                                                                     parameters.numberOfThreads);
                SWCHelper swcHelper = null;
                Object coordinatesToSave = null;

                if (parameters.localToWorld) {

                    final List<List<TileCoordinates>> loadedLocalCoordinates =
                            loadJsonArrayOfArraysOfCoordinates(parameters.fromJson);
                    coordinatesToSave = client.localToWorldInBatches(loadedLocalCoordinates);

                } else if (parameters.toStack == null) {

                    final List<TileCoordinates> worldCoordinates = loadJsonArrayOfCoordinates(parameters.fromJson);
                    coordinatesToSave = client.worldToLocalInBatches(worldCoordinates);

                } else {


                    final RenderDataClient targetRenderDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                                                         parameters.getToOwner(),
                                                                                         parameters.getToProject());

                    final CoordinateClient targetClient = new CoordinateClient(parameters.toStack,
                                                                               null,
                                                                               targetRenderDataClient,
                                                                               parameters.numberOfThreads);

                    final List<TileCoordinates> worldCoordinates;

                    if (parameters.fromSwcDirectory == null) {

                        worldCoordinates = loadJsonArrayOfCoordinates(parameters.fromJson);

                    } else {

                        swcHelper = new SWCHelper(client.getStackVersion(), targetClient.getStackVersion());
                        worldCoordinates = new ArrayList<>();

                        swcHelper.addCoordinatesForAllFilesInDirectory(parameters.fromSwcDirectory,
                                                                       worldCoordinates);

                    }

                    final List<TileCoordinates> targetWorldCoordinates =
                            targetClient.localToWorldInBatches(
                                    client.worldToLocalInBatches(worldCoordinates));

                    if (swcHelper == null) {
                        coordinatesToSave = targetWorldCoordinates;
                    } else {
                        swcHelper.saveMappedResults(targetWorldCoordinates,
                                                    parameters.toSwcDirectory);
                    }

                }

                if (swcHelper == null) {
                    FileUtil.saveJsonFile(parameters.toJson, coordinatesToSave);
                }

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

    public StackVersion getStackVersion() throws IOException {

        final StackMetaData stackMetaData = renderDataClient.getStackMetaData(stack);
        final StackVersion stackVersion = stackMetaData.getCurrentVersion();

        if ((stackVersion == null) ||
            (stackVersion.getStackResolutionX() == null) ||
            (stackVersion.getStackResolutionY() == null) ||
            (stackVersion.getStackResolutionZ() == null)) {
            throw new IOException("SWC mapping cannot be performed because stack " +
                                  stackMetaData.getStackId() +
                                  " does not have resolution attributes.  " +
                                  "Data for the current stack version is: " + stackVersion);
        }

        return stackVersion;
    }

    public List<List<TileCoordinates>> getWorldCoordinatesWithTileIds(final List<TileCoordinates> worldCoordinatesList)
            throws IOException {
        return renderDataClient.getTileIdsForCoordinates(worldCoordinatesList,
                                                         stack,
                                                         z);
    }

    public List<List<TileCoordinates>> worldToLocalInBatches(final List<TileCoordinates> loadedWorldCoordinates)
            throws IOException, InterruptedException {

        final BatchHelper batchHelper = new BatchHelper("worldToLocalInBatches", loadedWorldCoordinates.size());

        final List<List<TileCoordinates>> localListOfLists = new ArrayList<>(batchHelper.totalCoordinatesToMap);

        for (int fromIndex = 0; fromIndex < batchHelper.totalCoordinatesToMap; fromIndex += batchHelper.batchSize) {

            final int toIndex = batchHelper.getToIndexAndLogStart(fromIndex);

            final List<List<TileCoordinates>> batchWorldCoordinatesWithTileIds =
                    getWorldCoordinatesWithTileIds(loadedWorldCoordinates.subList(fromIndex,
                                                                                  toIndex));
            final ResolvedTileSpecCollection tiles = getTiles(batchWorldCoordinatesWithTileIds);
            localListOfLists.addAll(worldToLocal(batchWorldCoordinatesWithTileIds, tiles));

            batchHelper.logCompletion(fromIndex, toIndex);
        }

        return localListOfLists;
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

    public List<TileCoordinates> localToWorldInBatches(final List<List<TileCoordinates>> loadedLocalCoordinates)
            throws IOException, InterruptedException {

        final BatchHelper batchHelper = new BatchHelper("localToWorldInBatches", loadedLocalCoordinates.size());

        final List<TileCoordinates> worldList = new ArrayList<>(batchHelper.totalCoordinatesToMap);

        for (int fromIndex = 0; fromIndex < batchHelper.totalCoordinatesToMap; fromIndex += batchHelper.batchSize) {

            final int toIndex = batchHelper.getToIndexAndLogStart(fromIndex);

            final List<List<TileCoordinates>> batchLocalCoordinates =
                    loadedLocalCoordinates.subList(fromIndex, toIndex);

            final ResolvedTileSpecCollection tiles = getTiles(batchLocalCoordinates);
            worldList.addAll(localToWorld(batchLocalCoordinates, tiles));

            batchHelper.logCompletion(fromIndex, toIndex);
        }

        return worldList;
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

    private ResolvedTileSpecCollection getTiles(final List<List<TileCoordinates>> listOfCoordinateLists) {

        final Set<String> tileIdSet = new HashSet<>(listOfCoordinateLists.size());
        for (final List<TileCoordinates> coordinatesList : listOfCoordinateLists) {
            for (final TileCoordinates coordinates : coordinatesList) {
                tileIdSet.add(coordinates.getTileId());
            }
        }

        final List<String> tileIdList = new ArrayList<>(tileIdSet.size());
        List<TileSpec> tileSpecList = new ArrayList<>(listOfCoordinateLists.size());
        try {

            final int maxTileIdsPerRequest = 50000;

            for (final String tileId : tileIdSet) {
                if (tileIdList.size() == maxTileIdsPerRequest) {
                    tileSpecList.addAll(renderDataClient.getTileSpecsWithIds(tileIdList, stack));
                    tileIdList.clear();
                }
                tileIdList.add(tileId);
            }

            if (tileIdList.size() > 0) {
                tileSpecList.addAll(renderDataClient.getTileSpecsWithIds(tileIdList, stack));
            }

        } catch (final Throwable t) {
            LOG.warn("failed to retrieve tile specs", t);
            tileSpecList = new ArrayList<>();
        }

        return new ResolvedTileSpecCollection(new ArrayList<TransformSpec>(), tileSpecList);
    }

    public static List<TileCoordinates> loadJsonArrayOfCoordinates(final String path)
            throws IOException {

        final List<TileCoordinates> parsedFromJson;

        LOG.info("loadJsonArrayOfCoordinates: entry");

        final Path fromPath = Paths.get(path).toAbsolutePath();

        try (final Reader reader = FileUtil.DEFAULT_INSTANCE.getExtensionBasedReader(fromPath.toString())) {
            parsedFromJson = TileCoordinates.fromJsonArray(reader);
        } catch (final Throwable t) {
            throw new IOException("failed to parse " + fromPath, t);
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
        } catch (final Throwable t) {
            throw new IOException("failed to parse " + fromPath, t);
        }

        LOG.info("loadJsonArrayOfArraysOfCoordinates: parsed {} coordinates from {}", parsedFromJson.size(), fromPath);

        return parsedFromJson;
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
            TileCoordinates coordinates;

            for (int i = startIndex; (i < stopIndex) && (i < worldListOfLists.size()); i++) {

                coordinates = null;
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
                final String zMessage = z == null ? "" : "layer " + z + " of ";
                throw new IllegalArgumentException("no tile specifications found in " + zMessage + "stack " + stack +
                                                   " for " + Arrays.toString(coordinatesList.get(0).getWorld()));
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

    private class BatchHelper {

        private final String methodName;
        private final int batchSize;
        private final int totalCoordinatesToMap;
        private long startTime;

        public BatchHelper(final String methodName,
                           final int totalCoordinatesToMap) {
            this.methodName = methodName;
            this.startTime = System.currentTimeMillis();
            this.totalCoordinatesToMap = totalCoordinatesToMap;
            this.batchSize = 25000;
        }

        public int getToIndexAndLogStart(final int fromIndex) {
            startTime = System.currentTimeMillis();
            final int toIndex = Math.min((fromIndex + batchSize), totalCoordinatesToMap);
            LOG.info("{}: processing points {} to {} of {}",
                     methodName, fromIndex + 1, toIndex, totalCoordinatesToMap);
            return toIndex;
        }

        public void logCompletion(final int fromIndex,
                                  final int toIndex) {
            final long elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000;
            final int elapsedMinutes = (int) ((elapsedSeconds / 60.0) + 0.5);
            final String elapsedTimeLog = elapsedMinutes > 0 ? elapsedMinutes + " minutes" :
                                          elapsedSeconds + " seconds";
            LOG.info("{}: mapped points {} to {} of {} in {}",
                     methodName, fromIndex + 1, toIndex, totalCoordinatesToMap, elapsedTimeLog);

        }
    }

    public static class SWCHelper {

        // SWC format specification: http://research.mssm.edu/cnic/swc.html

        private final StackVersion sourceStackVersion;
        private final StackVersion targetStackVersion;
        private final Map<File, int[]> fileToIndexRangeMap;
        private final Pattern readPattern = Pattern.compile("^\\d+ \\d+ (\\S+) (\\S+) (\\S+) .+");
        private final Pattern writePattern = Pattern.compile("^(\\d+ \\d+ )\\S+ \\S+ (\\S+)( .+)");

        public SWCHelper(final StackVersion sourceStackVersion,
                         final StackVersion targetStackVersion) {
            this.sourceStackVersion = sourceStackVersion;
            this.targetStackVersion = targetStackVersion;
            this.fileToIndexRangeMap = new LinkedHashMap<>();
        }

        public void addCoordinatesForAllFilesInDirectory(final String directoryPath,
                                                         final List<TileCoordinates> coordinatesList)
                throws IOException {

            try (DirectoryStream<Path> directoryStream =
                         Files.newDirectoryStream(Paths.get(directoryPath), "*.swc")) {
                for (final Path path : directoryStream) {
                    addCoordinatesForFile(new File(path.toString()),
                                          coordinatesList);
                }
            }

        }

        public List<TileCoordinates> addCoordinatesForFile(final File sourceFile,
                                                           final List<TileCoordinates> coordinatesList)
                throws IOException {

            final int startIndex = coordinatesList.size();

            final double xPerPixel = sourceStackVersion.getStackResolutionX();
            final double yPerPixel = sourceStackVersion.getStackResolutionY();
            final double zPerPixel = sourceStackVersion.getStackResolutionZ();

            try (BufferedReader reader = new BufferedReader(new FileReader(sourceFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    final Matcher m = readPattern.matcher(line);
                    if (m.matches()) {
                        final double[] pixelCoordinates = {
                                Double.parseDouble(m.group(1)) / xPerPixel,
                                Double.parseDouble(m.group(2)) / yPerPixel,
                                roundDoubleToInt(Double.parseDouble(m.group(3)) / zPerPixel) // force z to integral value
                        };
                        coordinatesList.add(TileCoordinates.buildWorldInstance(null, pixelCoordinates));
                    }
                }
            } catch (final Throwable t) {
                throw new IOException("failed to parse " + sourceFile.getAbsolutePath(), t);
            }

            final int stopIndex = coordinatesList.size();

            if (stopIndex > startIndex) {
                fileToIndexRangeMap.put(sourceFile, new int[] {startIndex, stopIndex});
            }

            return coordinatesList;
        }

        public void saveMappedResults(final List<TileCoordinates> coordinatesList,
                                      final String targetDirectoryPath)
                throws IOException {

            final StringBuilder failureData = new StringBuilder();

            final double xPerPixel = targetStackVersion.getStackResolutionX();
            final double yPerPixel = targetStackVersion.getStackResolutionY();
            final double zPerPixel = targetStackVersion.getStackResolutionZ();
            final double sourceZPerPixel = sourceStackVersion.getStackResolutionZ();

            final File targetDirectory = new File(targetDirectoryPath).getAbsoluteFile();

            for (final File sourceFile : fileToIndexRangeMap.keySet()) {

                final int[] range = fileToIndexRangeMap.get(sourceFile);
                final List<TileCoordinates> coordinatesForFile = coordinatesList.subList(range[0], range[1]);
                final File targetFile = new File(targetDirectory, sourceFile.getName());

                int i = 0;
                try (BufferedReader reader = new BufferedReader(new FileReader(sourceFile));
                     BufferedWriter writer = new BufferedWriter(new FileWriter(targetFile))) {

                    String line;
                    TileCoordinates tileCoordinates;
                    double[] world;
                    while ((line = reader.readLine()) != null) {

                        final Matcher m = writePattern.matcher(line);

                        if (m.matches()) {

                            tileCoordinates = coordinatesForFile.get(i);
                            world = tileCoordinates.getWorld();

                            writer.write(m.group(1));

                            if ((world != null) && (world.length > 2)) {
                                writer.write((world[0] * xPerPixel) + " " +
                                             (world[1] * yPerPixel) + " " +
                                             getTargetZ(m.group(2), sourceZPerPixel, world[2], zPerPixel));
                            } else {
                                writer.write("-999 -999 -999"); // mark error with bad coordinates but keep swc references intact
                                failureData.append(sourceFile.getAbsolutePath()).append(": ").append(line).append("\n");
                            }

                            writer.write(m.group(3));
                            writer.newLine();

                            i++;

                        } else {
                            writer.write(line);
                            writer.newLine();
                        }

                    }

                    LOG.info("saved {}", targetFile.getAbsolutePath());

                } catch (final Throwable t) {
                    throw new IOException("failed to parse " + sourceFile.getAbsolutePath(), t);
                }

            }

            if (failureData.length() > 0) {
                final File failuresFile = new File(targetDirectory, "failed_mappings.txt");
                Files.write(Paths.get(failuresFile.getAbsolutePath()), failureData.toString().getBytes());
                LOG.warn("saved failed mapping details in {}", failuresFile.getAbsolutePath());
            }

        }

        private int roundDoubleToInt(final double value) {
            return (int) (value + 0.5);
        }

        private double getTargetZ(final String sourceZString,
                                  final double sourceZPerPixel,
                                  final double targetZPixels,
                                  final double targetZPerPixel) {

            final double sourceZ = Double.parseDouble(sourceZString);
            final double sourceZPixels = sourceZ / sourceZPerPixel;
            final double remainderZPixels = sourceZPixels - roundDoubleToInt(sourceZPixels);
            return (targetZPixels + remainderZPixels) * targetZPerPixel;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(CoordinateClient.class);

}

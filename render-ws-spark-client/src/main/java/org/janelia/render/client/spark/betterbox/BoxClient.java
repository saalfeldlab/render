package org.janelia.render.client.spark.betterbox;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.Serializable;
import java.net.InetAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.spark.SparkConf;
import org.apache.spark.TaskContext;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.PairFlatMapFunction;
import org.apache.spark.broadcast.Broadcast;
import org.janelia.alignment.betterbox.BoxData;
import org.janelia.alignment.betterbox.BoxDataPyramidForLayer;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileBoundsRTree;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.alignment.util.LabelImageProcessorCache;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.IGridPaths;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.betterbox.BoxGenerator;
import org.janelia.render.client.betterbox.LabelBoxValidationResult;
import org.janelia.render.client.betterbox.LabelBoxValidator;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MaterializedBoxParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.ZRangeParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import scala.Tuple2;

/**
 * Spark client for rendering uniform (but arbitrarily sized) boxes to disk for one or more layers.
 *
 * Before rendering, a "plan" is built in an attempt to distribute work as evenly as possible
 * across the Spark cluster.  The client then iterates through each mipmap level using the
 * {@link BoxGenerator} utility to render that level's boxes for all layers of the stack.
 *
 * To facilitate analysis of work distribution, the client supports an --explainPlan option
 * that logs what will be rendered where without actually doing the rendering.
 *
 * @author Eric Trautman
 */
public class BoxClient
        implements Serializable {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @ParametersDelegate
        MaterializedBoxParameters box = new MaterializedBoxParameters();

        @ParametersDelegate
        public ZRangeParameters layerRange = new ZRangeParameters();

        @Parameter(
                names = "--cleanUpPriorRun",
                description = "Indicates that you are rerunning a job that previously failed.  " +
                              "If set, the last boxes rendered for each prior partition will be removed " +
                              "because they are potentially corrupt.  All other previously rendered boxes " +
                              "will be excluded from this run and kept.",
                arity = 0)
        public boolean cleanUpPriorRun = false;

        @Parameter(
                names = "--explainPlan",
                description = "Run through partition stages and log 'the plan', but skip actual rendering",
                arity = 0)
        public boolean explainPlan = false;

        @Parameter(
                names = { "--maxImageCacheGb" },
                description = "Maximum number of gigabytes of source level zero pixel data to cache per core.  " +
                              "WARNING: When increasing this value, make sure to monitor performance and pay close " +
                              "attention to JVM garbage collection times.  " +
                              "Increases in cache size can non-intuitively degrade overall performance."
        )
        public Double maxCacheGb = 1.0;

        @Parameter(
                names = "--validateLabelsOnly",
                description = "Run validation process on already generated label boxes and render nothing",
                arity = 0)
        public boolean validateLabelsOnly = false;

        @Parameter(
                names = "--z",
                description = "Explicit z values for layers to be rendered",
                variableArity = true) // e.g. --z 20.0 --z 21.0 --z 22.0
        public List<Double> zValues;
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final BoxClient client = new BoxClient(parameters);
                client.run(new SparkConf().setAppName(client.getClass().getSimpleName()));
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    private BoxGenerator boxGenerator;
    private List<Double> zValues;
    private File boxDataParentDirectory;
    private File partitionedBoxDataDirectory;
    private File labelValidationDirectory;

    BoxClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    public void run(final SparkConf sparkConf)
            throws IOException {

        final JavaSparkContext sparkContext = new JavaSparkContext(sparkConf);

        LogUtilities.logSparkClusterInfo(sparkContext);

        setupForRun();

        boolean foundBoxesRenderedForPriorRun = false;
        if (parameters.cleanUpPriorRun) {
            foundBoxesRenderedForPriorRun = cleanUpPriorRun(sparkContext);
        }

        final JavaRDD<BoxData> distributedBoxDataRdd = partitionBoxes(sparkContext,
                                                                      foundBoxesRenderedForPriorRun);

        final Broadcast<BoxGenerator> broadcastBoxGenerator = sparkContext.broadcast(boxGenerator);

        if (parameters.validateLabelsOnly) {
            validateLabelBoxes(sparkContext, distributedBoxDataRdd);
        } else {
            for (int level = 0; level <= parameters.box.maxLevel; level++) {
                renderBoxesForLevel(level, distributedBoxDataRdd, broadcastBoxGenerator);
            }
        }

        if (parameters.box.isOverviewNeeded() && (! parameters.explainPlan) && (! parameters.validateLabelsOnly)) {
            renderOverviewImages(sparkContext,
                                 broadcastBoxGenerator);
        }

        LogUtilities.logSparkClusterInfo(sparkContext); // log cluster info again here to add run stats to driver log

        sparkContext.stop();
    }

    /**
     * Retrieve and save basic stack information from the web service so that it can be used for the run.
     * Create any base directories and shared files needed by later distributed processing stages.
     */
    private void setupForRun()
            throws IOException {

        final RenderDataClient driverDataClient = parameters.renderWeb.getDataClient();

        final StackMetaData stackMetaData = driverDataClient.getStackMetaData(parameters.box.stack);
        final Bounds stackBounds = stackMetaData.getStats().getStackBounds();

        if (parameters.validateLabelsOnly) {
            parameters.box.label = true;
            parameters.box.maxLevel = 0;
        }

        boxGenerator = new BoxGenerator(parameters.renderWeb, parameters.box, stackBounds);
        if ((! parameters.explainPlan) && (! parameters.validateLabelsOnly)) {
            boxGenerator.setupCommonDirectoriesAndFiles();
        }

        this.zValues = driverDataClient.getStackZValues(parameters.box.stack,
                                                        parameters.layerRange.minZ,
                                                        parameters.layerRange.maxZ,
                                                        parameters.zValues);

        // insert IP address into directory name to prevent data collisions when multiple drivers (Spark jobs)
        // are concurrently launched for the same stack (Allen Brain Institute use case)
        final String timestamp = new SimpleDateFormat("yyyy_MMdd_HHmm_ss").format(new Date());
        final String runName = "run." + timestamp + ".driver." + InetAddress.getLocalHost().getHostAddress();

        if (parameters.validateLabelsOnly) {

            final String validationDirName = "label_val_" + new Date().getTime();
            labelValidationDirectory = new File(boxGenerator.getBaseBoxPath(), validationDirName).getAbsoluteFile();
            boxDataParentDirectory = new File(labelValidationDirectory, "box_data");

        } else {

            boxDataParentDirectory = new File(boxGenerator.getBaseBoxPath(), "box_data");

        }

        partitionedBoxDataDirectory = new File(boxDataParentDirectory, runName);
    }

    /**
     * Look for prior run data and clean-up potentially corrupted images from the most recent failed prior run.
     * Leave as much existing data as possible in place so that it does not need to be regenerated.
     *
     * @param  sparkContext  context for current run.
     *
     * @return true if prior data was found; otherwise false.
     */
    private boolean cleanUpPriorRun(final JavaSparkContext sparkContext) {

        List<String> removedBoxPaths = new ArrayList<>();

        JavaRDD<String> priorRunBoxDataStringsRdd = null;

        final File levelZeroDirectory = new File(boxGenerator.getBaseBoxPath(), "0");
        if (levelZeroDirectory.exists() && boxDataParentDirectory.exists()) {

            final FilenameFilter numberedDirFilter = (dir, name) -> name.matches("^\\d++$");
            final File[] zDirectories = levelZeroDirectory.listFiles(numberedDirFilter);
            if ((zDirectories != null) && (zDirectories.length > 0)) {

                LOG.info("cleanUpPriorRun: found materialized data in {}", levelZeroDirectory);

                // at least one z directory exists, so look for and load partition data from the last run
                final List<File> partitionDirectories =
                        Arrays.asList(Objects.requireNonNull(boxDataParentDirectory.listFiles(File::isDirectory)));

                // reverse sort the list so that the last run is first
                partitionDirectories.sort((o1, o2) -> o2.getName().compareTo(o1.getName()));

                if (partitionDirectories.size() > 0) {
                    final File latestPartitionDirectory = partitionDirectories.get(0);
                    LOG.info("cleanUpPriorRun: found prior run partition directory {}", latestPartitionDirectory);
                    priorRunBoxDataStringsRdd = sparkContext.textFile(latestPartitionDirectory.getAbsolutePath());
                }

            } else {
                LOG.warn("cleanUpPriorRun: skipping because no materialized data was found in {}",
                         levelZeroDirectory);
            }

        } else {
            LOG.warn("cleanUpPriorRun: skipping because {} and/or {} are missing",
                     levelZeroDirectory, boxDataParentDirectory);
        }

        if (priorRunBoxDataStringsRdd != null) {

            final String baseBoxPath = boxGenerator.getBaseBoxPath();
            final String pathSuffix = boxGenerator.getBoxPathSuffix();

            final JavaRDD<String> removedBoxPathsRdd = priorRunBoxDataStringsRdd.mapPartitions(

                    (FlatMapFunction<Iterator<String>, String>) stringIterator -> {

                        final List<String> removedPaths = new ArrayList<>();

                        BoxData lastBoxData = null;
                        BoxData boxData;
                        File boxFile;
                        while (stringIterator.hasNext()) {
                            boxData = BoxData.fromString(stringIterator.next());
                            boxFile = boxData.getAbsoluteLevelFile(baseBoxPath, pathSuffix);
                            if (boxFile.exists()) {
                                lastBoxData = boxData;
                            } else {
                                break;
                            }
                        }

                        if (lastBoxData != null) {
                            removeBoxFileAndParentFiles(lastBoxData,
                                                        baseBoxPath,
                                                        pathSuffix,
                                                        removedPaths,
                                                        parameters.box.maxLevel);
                        }

                        return removedPaths.iterator();
                    }
            );

            removedBoxPaths = new ArrayList<>(removedBoxPathsRdd.collect());
            Collections.sort(removedBoxPaths);

            LOG.info(""); // empty statement adds newline to lengthy unterminated stage progress lines in log
            LOG.info("cleanUpPriorRun: removed {} box images: {}", removedBoxPaths.size(), removedBoxPaths);
        }

        return (removedBoxPaths.size() > 0);
    }

    /**
     * On workers (in parallel), build box data pyramids using layer tile bounds.
     * Partition the data so that the box data for each mipmap level is evenly distributed across the cluster.
     *
     * @param  sparkContext                 context for current run.
     *
     * @param  excludeAlreadyRenderedBoxes  indicates whether existing rendered box images (presumably from prior runs)
     *                                      should be excluded from this run.
     *
     * @return optimally partitioned box data set for rendering.
     */
    private JavaRDD<BoxData> partitionBoxes(final JavaSparkContext sparkContext,
                                            final boolean excludeAlreadyRenderedBoxes) {

        final JavaRDD<Double> zValuesRdd = sparkContext.parallelize(zValues);

        final JavaPairRDD<Double, BoxDataPyramidForLayer> zToPyramidPairRdd = zValuesRdd.mapPartitionsToPair(
                (PairFlatMapFunction<Iterator<Double>, Double, BoxDataPyramidForLayer>) zIterator -> {

                    LogUtilities.setupExecutorLog4j("partition " + TaskContext.getPartitionId());

                    final List<Tuple2<Double, BoxDataPyramidForLayer>> zToPyramidList = new ArrayList<>();

                    final RenderDataClient localDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                                                  parameters.renderWeb.owner,
                                                                                  parameters.renderWeb.project);
                    final String stack = parameters.box.stack;

                    double z;
                    while (zIterator.hasNext()) {
                        z = zIterator.next();
                        final Bounds layerBounds = localDataClient.getLayerBounds(stack, z);
                        final List<TileBounds> tileBoundsList = localDataClient.getTileBounds(stack, z);
                        final BoxDataPyramidForLayer boxPyramid =
                                new BoxDataPyramidForLayer(z,
                                                           layerBounds,
                                                           parameters.box.width,
                                                           parameters.box.height,
                                                           tileBoundsList,
                                                           parameters.box.maxLevel,
                                                           excludeAlreadyRenderedBoxes,
                                                           boxGenerator.getBaseBoxPath(),
                                                           boxGenerator.getBoxPathSuffix());

                        zToPyramidList.add(new Tuple2<>(z, boxPyramid));

                        // if DMG iGrid files have been requested and this is a first run,
                        // create iGrid file for level zero boxes

                        if (parameters.box.createIGrid && (! excludeAlreadyRenderedBoxes)) {

                            int maxRow = 0;
                            int maxColumn = 0;
                            final List<BoxData> levelZeroBoxes = new ArrayList<>();
                            for (final BoxData boxData : boxPyramid.getPyramidList()) {
                                if (boxData.getLevel() == 0) {
                                    levelZeroBoxes.add(boxData);
                                    maxRow = Math.max(boxData.getRow(), maxRow);
                                    maxColumn = Math.max(boxData.getColumn(), maxColumn);
                                }
                            }

                            final String baseBoxPath = boxGenerator.getBaseBoxPath();
                            final IGridPaths iGridPaths = new IGridPaths(maxRow + 1, maxColumn + 1);
                            iGridPaths.addBoxes(baseBoxPath,
                                                boxGenerator.getBoxPathSuffix(),
                                                levelZeroBoxes);

                            final Path iGridDirectory = Paths.get(baseBoxPath, "iGrid");
                            iGridPaths.saveToFile(iGridDirectory.toFile(), z, boxGenerator.getEmptyImageFile());
                        }

                    }

                    return zToPyramidList.iterator();
                });

        // cache pyramid data in worker memory so that is doesn't have to be rebuilt when we partition everything later
        zToPyramidPairRdd.cache();

        final JavaPairRDD<Double, List<Integer>> zToLevelBoxCountsPairRdd = zToPyramidPairRdd.mapValues(
                (Function<BoxDataPyramidForLayer, List<Integer>>) pyramid -> {

                    LogUtilities.setupExecutorLog4j("partition " + TaskContext.getPartitionId());
                    final Logger log = LoggerFactory.getLogger(BoxData.class);

                    final List<Integer> levelBucketCounts = pyramid.getLevelBoxCounts();

                    log.info("layer {} has {} total boxes, level box counts are {}",
                             pyramid.getZ(), pyramid.getSize(), levelBucketCounts);

                    return levelBucketCounts;
                }
        );

        final Map<Double, List<Integer>> zToLevelBoxCountsMap = zToLevelBoxCountsPairRdd.collectAsMap();

        LOG.info(""); // empty statement adds newline to lengthy unterminated stage progress lines in log
        LOG.info("partitionBoxes: collected level box counts");

        final BoxPartitioner boxPartitioner = new BoxPartitioner(sparkContext.defaultParallelism(),
                                                                 zToLevelBoxCountsMap);

        LOG.info("partitionBoxes: built {}", boxPartitioner);

        final JavaPairRDD<BoxData, BoxData> boxKeyPairRdd =
                zToPyramidPairRdd.mapPartitionsToPair(
                        (PairFlatMapFunction<Iterator<Tuple2<Double, BoxDataPyramidForLayer>>, BoxData, BoxData>) pyramidIterator -> {

                            final List<Tuple2<BoxData, BoxData>> list = new ArrayList<>();

                            BoxDataPyramidForLayer pyramid;
                            while (pyramidIterator.hasNext()) {
                                pyramid = pyramidIterator.next()._2;

                                list.addAll(pyramid.getPyramidList()
                                                    .stream()
                                                    .map(boxKey -> new Tuple2<>(boxKey, boxKey))
                                                    .collect(Collectors.toList()));
                            }

                            return list.iterator();
                        }
                );

        final JavaPairRDD<BoxData, BoxData> repartitionedBoxKeyPairRdd =
                boxKeyPairRdd.partitionBy(boxPartitioner);

        final JavaRDD<BoxData> repartitionedKeysRdd = repartitionedBoxKeyPairRdd.keys();

        // write the partitioned data to disk so that Spark will redistribute it properly
        // and ignore the partitions we used to derive the data
        repartitionedKeysRdd.saveAsTextFile(partitionedBoxDataDirectory.getAbsolutePath());

        LOG.info(""); // empty statement adds newline to lengthy unterminated stage progress lines in log
        LOG.info("partitionBoxes: saved partitioned box data in {}", partitionedBoxDataDirectory.getAbsolutePath());

        // remove cached pyramid data from worker memory since we no longer need it
        zToPyramidPairRdd.unpersist(false);

        // load the partitioned serialized box data from disk ...
        final JavaRDD<String> redistributedBoxDataStringsRdd =
                sparkContext.textFile(partitionedBoxDataDirectory.getAbsolutePath());

        // and deserialize it into the RDD we want for rendering
        return redistributedBoxDataStringsRdd.mapPartitions(

                (FlatMapFunction<Iterator<String>, BoxData>) stringIterator -> {
                    final List<BoxData> boxDataList = new ArrayList<>();
                    while (stringIterator.hasNext()) {
                        boxDataList.add(BoxData.fromString(stringIterator.next()));
                    }
                    return boxDataList.iterator();
                }
        );

    }

    /**
     * Iterates through the box data, pulling any boxes with the specified level, and then renders those boxes.
     * Iterating level by level in order is important since only boxes in the same level can be safely
     * rendered in parallel (and each subsequent level depends upon the preceding level's rendered boxes).
     *
     * @param  level                  current level to render.
     * @param  boxDataRdd             all box data for this run.
     * @param  broadcastBoxGenerator  box generator broadcast to all worker nodes.
     */
    private void renderBoxesForLevel(final int level,
                                     final JavaRDD<BoxData> boxDataRdd,
                                     final Broadcast<BoxGenerator> broadcastBoxGenerator) {

        LOG.info("renderBoxesForLevel: entry, level={}", level);

        if (parameters.explainPlan) {

            logBoxRenderTaskInfo(level, boxDataRdd, broadcastBoxGenerator);

        } else {

            final JavaRDD<Integer> renderedBoxCountRdd =
                    boxDataRdd.mapPartitions(
                            (FlatMapFunction<Iterator<BoxData>, Integer>) boxDataIterator -> {

                                LogUtilities.setupExecutorLog4j("partition " + TaskContext.getPartitionId());
                                final Logger log = LoggerFactory.getLogger(BoxClient.class);

                                final Map<Double, List<BoxData>> zToBoxList = getBoxesToRender(level, boxDataIterator);

                                final List<BoxData> renderedBoxes = new ArrayList<>();

                                ImageProcessorCache imageProcessorCache = ImageProcessorCache.DISABLED_CACHE;

                                final long maxCachedPixels = (long) (1_000_000_000L * parameters.maxCacheGb);
                                if ((level == 0) && (! parameters.box.label)) {
                                    imageProcessorCache = new ImageProcessorCache(maxCachedPixels,
                                                                                  true,
                                                                                  false);
                                }

                                final BoxGenerator localBoxGenerator = broadcastBoxGenerator.getValue();

                                for (final Double z : zToBoxList.keySet()) {

                                    log.info("rendering boxes for z " + z);

                                    if ((parameters.box.label) && (level == 0)) {
                                        imageProcessorCache =
                                                getLevelZeroLabelImageProcessorCache(maxCachedPixels,
                                                                                     parameters.renderWeb,
                                                                                     parameters.box.stack,
                                                                                     z);
                                    }

                                    renderedBoxes.addAll(
                                            localBoxGenerator.renderBoxesForLevel(z,
                                                                                  level,
                                                                                  zToBoxList.get(z),
                                                                                  imageProcessorCache,
                                                                                  false)
                                    );
                                }

                                return Collections.singletonList(renderedBoxes.size()).iterator();
                            },
                            true
                    );

            long totalNumberOfRenderedBoxes = 0;
            for (final Integer partitionCount : renderedBoxCountRdd.collect()) {
                totalNumberOfRenderedBoxes += partitionCount;
            }

            LOG.info(""); // empty statement adds newline to lengthy unterminated stage progress lines in log
            LOG.info("renderBoxesForLevel: exit, rendered {} boxes for level {}", totalNumberOfRenderedBoxes, level);
        }

    }

    /**
     * This method iterates through the box data in the same manner as {@link #renderBoxesForLevel},
     * but instead of rendering images it simply tracks information about the worker, partition, and thread
     * where rendering would normally occur.  This allows us to collect that information on the driver
     * and log it there for analysis.
     *
     * @param  level                  current level to simulate rendering.
     * @param  boxDataRdd             all box data for this run.
     * @param  broadcastBoxGenerator  box generator broadcast to all worker nodes.
     */
    private void logBoxRenderTaskInfo(final int level,
                                      final JavaRDD<BoxData> boxDataRdd,
                                      final Broadcast<BoxGenerator> broadcastBoxGenerator) {

        final JavaRDD<String> boxTaskInfoRdd = boxDataRdd.mapPartitions(
                (FlatMapFunction<Iterator<BoxData>, String>) boxDataIterator -> {

                    String threadName = Thread.currentThread().getName();

                    // shorten thread name if possible
                    final Pattern p = Pattern.compile(".*(\\d++).*");
                    final Matcher m = p.matcher(threadName);
                    if (m.matches() && (m.groupCount() == 1)) {
                        threadName = String.format("task-%03d", Integer.parseInt(m.group(1)));
                    }

                    final String taskInfo =
                            String.format("stage: %03d, host: %s, partition: %03d, thread: %s",
                                          TaskContext.get().stageId(), InetAddress.getLocalHost().getHostName(),
                                          TaskContext.getPartitionId(), threadName);

                    final List<BoxData> renderableBoxes = new ArrayList<>();

                    final Map<Double, List<BoxData>> zToBoxList = getBoxesToRender(level, boxDataIterator);

                    for (final Double z : zToBoxList.keySet()) {

                        final BoxGenerator localBoxGenerator = broadcastBoxGenerator.getValue();
                        renderableBoxes.addAll(
                                localBoxGenerator.renderBoxesForLevel(z,
                                                                      level,
                                                                      zToBoxList.get(z),
                                                                      ImageProcessorCache.DISABLED_CACHE,
                                                                      true)
                        );
                    }

                    int levelBoxCount = 0;
                    for (final List<BoxData> boxList : zToBoxList.values()) {
                        levelBoxCount += boxList.size();
                    }

                    final String countInfo =
                            String.format(", renderedBoxCounts: {total: %4d, level: %4d, parent: %4d}, zValues: %s, boxList: [ ",
                                          renderableBoxes.size(), levelBoxCount,
                                          (renderableBoxes.size() - levelBoxCount),
                                          zToBoxList.keySet());

                    final StringBuilder sb = new StringBuilder(taskInfo).append(countInfo);

                    final int maxNumberOfBoxesToAppend = 50;
                    int index = 0;
                    for (final BoxData renderedBox : renderableBoxes) {
                        if (index == maxNumberOfBoxesToAppend) {
                            sb.append("..., ");
                            break;
                        }
                        sb.append(renderedBox.toDelimitedString('_')).append(", ");
                        index++;
                    }

                    if (renderableBoxes.size() > 0) {
                        sb.setLength(sb.length() - 2);
                    }

                    sb.append(" ]");

                    return Collections.singletonList(sb.toString()).iterator();
                }
        );

        final List<String> taskInfoList =
                boxTaskInfoRdd.collect()
                        .stream()
                        .map(task -> "\n" + task)
                        .sorted()
                        .collect(Collectors.toList());

        LOG.info(""); // empty statement adds newline to lengthy unterminated stage progress lines in log
        LOG.info("logBoxRenderTaskInfo: exit, info for level {} is {}", level, taskInfoList);
    }

    private void validateLabelBoxes(final JavaSparkContext sparkContext,
                                    final JavaRDD<BoxData> boxDataRdd) {

        final File validationDirectory = labelValidationDirectory;

        LOG.info("validateLabelBoxes: entry, validationDirectory is {}", validationDirectory);

        final JavaRDD<Double> validatedZRdd =
                boxDataRdd.mapPartitions(
                        (FlatMapFunction<Iterator<BoxData>, Double>) boxDataIterator -> {

                            LogUtilities.setupExecutorLog4j("partition " + TaskContext.getPartitionId());

                            final List<BoxData> boxesToValidate = new ArrayList<>();
                            while (boxDataIterator.hasNext()) {
                                boxesToValidate.add(boxDataIterator.next());
                            }

                            final LabelBoxValidator validator = new LabelBoxValidator(parameters.renderWeb,
                                                                                      parameters.box);

                            final LabelBoxValidationResult result = validator.validateLabelBoxes(boxesToValidate);

                            result.writeLayerResults(validationDirectory, TaskContext.getPartitionId());

                            return result.getZValues().iterator();
                        },
                        true
                );

        final List<Double> distinctValidatedZValues = new ArrayList<>(new HashSet<>(validatedZRdd.collect()));

        final JavaRDD<Double> distinctValidatedZRdd = sparkContext.parallelize(distinctValidatedZValues);

        final JavaRDD<String> errorMessageRdd =
                distinctValidatedZRdd.mapPartitions((FlatMapFunction<Iterator<Double>, String>) zIterator -> {
                    LogUtilities.setupExecutorLog4j("partition " + TaskContext.getPartitionId());

                    final List<String> errorMessageList = new ArrayList<>();

                    final RenderDataClient localDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                                                  parameters.renderWeb.owner,
                                                                                  parameters.renderWeb.project);
                    final String stack = parameters.box.stack;

                    Double z;
                    LabelBoxValidationResult mergedResult;
                    while (zIterator.hasNext()) {

                        z = zIterator.next();

                        final List<TileBounds> tileBoundsList = localDataClient.getTileBounds(stack, z);
                        final TileBoundsRTree tileBoundsRTree = new TileBoundsRTree(z, tileBoundsList);
                        final Set<String> completelyObscuredTileIds =
                                tileBoundsRTree.findCompletelyObscuredTiles()
                                        .stream()
                                        .map(TileBounds::getTileId)
                                        .collect(Collectors.toSet());

                        mergedResult = LabelBoxValidationResult.fromDirectory(validationDirectory, z);
                        mergedResult.checkLabelCounts(z, completelyObscuredTileIds);
                        errorMessageList.addAll(mergedResult.getErrorMessageList(z));
                    }

                    return errorMessageList.iterator();
                });

        final List<String> errorMessageList =
                errorMessageRdd.collect()
                        .stream()
                        .map(msg -> "\n" + msg)
                        .collect(Collectors.toList());

        LOG.info(""); // empty statement adds newline to lengthy unterminated stage progress lines in log
        LOG.info("validateLabelBoxes: exit, {} errors found:\n{}", errorMessageList.size(), errorMessageList);
    }

    /**
     * Renders CATMAID overview ('small') images for each layer.
     *
     * @param  sparkContext           context for current run.
     * @param  broadcastBoxGenerator  box generator broadcast to all worker nodes.
     */
    private void renderOverviewImages(final JavaSparkContext sparkContext,
                                      final Broadcast<BoxGenerator> broadcastBoxGenerator) {

        final JavaRDD<Double> zValuesRdd = sparkContext.parallelize(zValues);

        final JavaRDD<Integer> renderedOverview = zValuesRdd.map((Function<Double, Integer>) z -> {

            final BoxGenerator localBoxGenerator = broadcastBoxGenerator.getValue();
            localBoxGenerator.renderOverview(z.intValue());
            return 1;
        });

        final long renderedOverviewCount = renderedOverview.count();

        LOG.info(""); // empty statement adds newline to lengthy unterminated stage progress lines in log
        LOG.info("run: rendered {} overview images", renderedOverviewCount);
    }

    /**
     * Aggregates the collection of boxes given to a Spark task into lists mapped by z value.
     *
     * @param  level            current level being rendered.
     * @param  boxDataIterator  iterator for boxes assigned to current task.
     *
     * @return map of z values to box lists for the current task.
     */
    private static Map<Double, List<BoxData>> getBoxesToRender(final int level,
                                                               final Iterator<BoxData> boxDataIterator) {

        final Map<Double, List<BoxData>> zToBoxList = new LinkedHashMap<>();
        List<BoxData> boxList;

        BoxData boxData;
        while (boxDataIterator.hasNext()) {

            boxData = boxDataIterator.next();

            if (boxData.getLevel() == level) {
                boxList = zToBoxList.computeIfAbsent(boxData.getZ(), k -> new ArrayList<>());
                boxList.add(boxData);
            }
        }

        zToBoxList.values().forEach(Collections::sort);

        return zToBoxList;
    }

    /**
     * @return label image processor cache.
     */
    private static ImageProcessorCache getLevelZeroLabelImageProcessorCache(final long maxCachedPixels,
                                                                            final RenderWebServiceParameters serviceParameters,
                                                                            final String stack,
                                                                            final Double z)
            throws IOException {

        // retrieve all tile specs for layer so that imageUrls can be consistently mapped to label colors
        // (this allows label runs to be resumed after failures)
        final RenderDataClient localRenderDataClient = new RenderDataClient(serviceParameters.baseDataUrl,
                                                                            serviceParameters.owner,
                                                                            serviceParameters.project);

        final ResolvedTileSpecCollection resolvedTiles = localRenderDataClient.getResolvedTiles(stack, z);

        return new LabelImageProcessorCache(maxCachedPixels,
                                            true,
                                            false,
                                            resolvedTiles.getTileSpecs());
    }

    /**
     * Removes the image file for the specified box from disk
     * as well as any existing image files for the box's parents.
     *
     * @param  boxData       box to remove.
     *
     * @param  baseBoxPath   the base path for all boxes being rendered
     *                       (e.g. /nrs/spc/rendered_boxes/spc/aibs_mm2_data/1024x1024).
     *
     * @param  pathSuffix    the suffix (format extension including '.') to append to each box path (e.g. '.jpg').
     *
     * @param  removedPaths  list of already removed paths.
     *                       Any paths removed by this method will be added to the list.
     *
     * @param  maxLevel      The maximum level parent to remove.
     */
    private static void removeBoxFileAndParentFiles(final BoxData boxData,
                                                    final String baseBoxPath,
                                                    final String pathSuffix,
                                                    final List<String> removedPaths,
                                                    final int maxLevel) {

        final File boxFile = boxData.getAbsoluteLevelFile(baseBoxPath, pathSuffix);

        if (boxFile.delete()) {

            removedPaths.add("\n" + boxFile);

            // try to remove all related parents since they are potentially obsolete

            BoxData parentBox = boxData.getParentBoxData();
            while (parentBox.getLevel() <= maxLevel) {

                final File parentBoxFile = parentBox.getAbsoluteLevelFile(baseBoxPath, pathSuffix);

                boolean foundAndDeletedParentFile = false;
                if (parentBoxFile.exists()) {
                    foundAndDeletedParentFile = parentBoxFile.delete();
                }

                if (foundAndDeletedParentFile) {
                    removedPaths.add("\n" + parentBoxFile);
                    parentBox = parentBox.getParentBoxData();
                } else {
                    break;
                }
            }


        } else {
            LOG.warn("prior run box {} already removed (hopefully by a parallel process)", boxFile);
        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(BoxClient.class);
}

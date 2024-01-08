package org.janelia.render.client.spark.match;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function2;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.janelia.alignment.match.RenderableCanvasIdPairsForCollection;
import org.janelia.alignment.match.cache.CanvasDataCache;
import org.janelia.alignment.match.parameters.MatchCommonParameters;
import org.janelia.alignment.match.parameters.MatchRunParameters;
import org.janelia.alignment.match.parameters.TilePairDerivationParameters;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.TilePairClient;
import org.janelia.render.client.match.InMemoryTilePairClient;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineParameters;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for generating and storing SIFT point matches.
 *
 * @author Eric Trautman
 */
public class MultiStagePointMatchClient
        implements Serializable, AlignmentPipelineStep {

    public static class Parameters extends CommandLineParameters {
        @ParametersDelegate
        public MultiProjectParameters multiProject = new MultiProjectParameters();
        @Parameter(
                names = "--matchRunJson",
                description = "JSON file where array of match run parameters are defined",
                required = true)
        public String matchRunJson;
    }


    /** Run the client with command line parameters. */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {
                final Parameters parameters = new Parameters();
                parameters.parse(args);
                final MultiStagePointMatchClient client = new MultiStagePointMatchClient();
                client.createContextAndRun(parameters);
            }
        };
        clientRunner.run();
    }

    /** Empty constructor required for alignment pipeline steps. */
    public MultiStagePointMatchClient() {
    }

    /** Create a spark context and run the client with the specified parameters. */
    public void createContextAndRun(final Parameters matchParameters) throws IOException {
        final SparkConf conf = new SparkConf().setAppName(getClass().getSimpleName());
        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            final String sparkAppId = sparkContext.getConf().getAppId();
            LOG.info("createContextAndRun: appId is {}", sparkAppId);
            final MultiProjectParameters multiProjectParameters = matchParameters.multiProject;
            final List<StackWithZValues> batchedList = multiProjectParameters.buildListOfStackWithBatchedZ();
            generatePairsAndMatchesForRunList(sparkContext,
                                              multiProjectParameters,
                                              batchedList,
                                              MatchRunParameters.fromJsonArrayFile(matchParameters.matchRunJson));
        }
    }

    /** Validates the specified pipeline parameters are sufficient. */
    @Override
    public void validatePipelineParameters(final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException {
        AlignmentPipelineParameters.validateRequiredElementExists("matchRunList",
                                                                  pipelineParameters.getMatchRunList());
    }

    /** Run the client as part of an alignment pipeline. */
    public void runPipelineStep(final JavaSparkContext sparkContext,
                                final AlignmentPipelineParameters pipelineParameters)
            throws IOException {

        final MultiProjectParameters multiProjectParameters = pipelineParameters.getMultiProject();
        final List<StackWithZValues> batchedList = multiProjectParameters.buildListOfStackWithBatchedZ();
        generatePairsAndMatchesForRunList(sparkContext,
                                          multiProjectParameters,
                                          batchedList,
                                          pipelineParameters.getMatchRunList());
    }

    private void generatePairsAndMatchesForRunList(final JavaSparkContext sparkContext,
                                                   final MultiProjectParameters multiProjectParameters,
                                                   final List<StackWithZValues> stackWithZValuesList,
                                                   final List<MatchRunParameters> matchRunList) {
        for (int i = 0; i < matchRunList.size(); i++) {
            LOG.info("generatePairsAndMatchesForRunList: starting run {} of {}", (i+1), matchRunList.size());
            generatePairsAndMatchesForRun(sparkContext,
                                          multiProjectParameters,
                                          stackWithZValuesList,
                                          matchRunList.get(i));
            clearDataLoaders(sparkContext);
        }
        LOG.info("generatePairsAndMatchesForRunList: exit");
    }

    private void generatePairsAndMatchesForRun(final JavaSparkContext sparkContext,
                                               final MultiProjectParameters multiProjectParameters,
                                               final List<StackWithZValues> stackWithZValuesList,
                                               final MatchRunParameters matchRunParameters) {

        LOG.info("generatePairsAndMatchesForRun: entry, {} stackWithZValuesList items, matchRunParameters={}",
                 stackWithZValuesList.size(), matchRunParameters.toJson());

        final MatchCommonParameters matchCommon = matchRunParameters.getMatchCommon();
        final TilePairDerivationParameters tilePairDerivation = matchRunParameters.getTilePairDerivationParameters();

        List<StackWithZValues> effectiveStackWithZValuesList = stackWithZValuesList;
        if (tilePairDerivation.zNeighborDistance > 0) {
            // for cross-match pair derivation, need to ensure stacks do not have specific z layers so reduce list here
            effectiveStackWithZValuesList = stackWithZValuesList.stream()
                    .map(StackWithZValues::getStackId)
                    .distinct()
                    .map(stackId -> new StackWithZValues(stackId, new ArrayList<>()))
                    .collect(Collectors.toList());
            LOG.info("generatePairsAndMatchesForRun: reduced stackWithZValuesList from {} to {} items for cross pair derivation",
                     stackWithZValuesList.size(), effectiveStackWithZValuesList.size());
        }

        final JavaRDD<RenderableCanvasIdPairsForCollection> rddCanvasIdPairsWithDrift =
                generateRenderableCanvasIdPairs(sparkContext,
                                                multiProjectParameters,
                                                effectiveStackWithZValuesList,
                                                tilePairDerivation,
                                                matchCommon.maxPairsPerStackBatch);

        final JavaRDD<RenderableCanvasIdPairsForCollection> rddCanvasIdPairs =
                repartitionPairsForBalancedProcessing(sparkContext, rddCanvasIdPairsWithDrift);

        final org.janelia.render.client.MultiStagePointMatchClient.Parameters multiStageParameters =
                new org.janelia.render.client.MultiStagePointMatchClient.Parameters(
                        matchCommon.featureStorage,
                        matchCommon.maxPeakCacheGb,
                        matchRunParameters.getMatchStageParametersList());

        final JavaRDD<Integer> rddSavedMatchPairCounts =
                generateAndStoreCanvasMatches(multiStageParameters,
                                              multiProjectParameters.baseDataUrl,
                                              rddCanvasIdPairs);
        
        final List<Integer> matchPairCountList = rddSavedMatchPairCounts.collect();

        LOG.info("generatePairsAndMatchesForRun: collected stats for {}", matchRunParameters.getRunName());

        long totalSaved = 0;
        for (final Integer matchCount : matchPairCountList) {
            totalSaved += matchCount;
        }

        LOG.info("generatePairsAndMatchesForRun: exit, saved matches for {} pairs on {} partitions for {}",
                 totalSaved, matchPairCountList.size(), matchRunParameters.getRunName());
    }

    private JavaRDD<RenderableCanvasIdPairsForCollection> generateRenderableCanvasIdPairs(final JavaSparkContext sparkContext,
                                                                                          final MultiProjectParameters multiProjectParameters,
                                                                                          final List<StackWithZValues> stackWithZValuesList,
                                                                                          final TilePairDerivationParameters tilePairDerivationParameters,
                                                                                          final int maxPairsPerStackBatch) {



        final JavaRDD<StackWithZValues> rddStackWithZValues = sparkContext.parallelize(stackWithZValuesList);

        LOG.info("generateRenderableCanvasIdPairs: process {} StackWithZValues objects across {} partitions",
                 stackWithZValuesList.size(), rddStackWithZValues.getNumPartitions());

        return rddStackWithZValues.mapPartitionsWithIndex(
                (Function2<Integer, Iterator<StackWithZValues>, Iterator<RenderableCanvasIdPairsForCollection>>) (partitionIndex, stackWithZValuesIterator) -> {

                    LogUtilities.setupExecutorLog4j("partition " + partitionIndex);
                    final Logger log = LoggerFactory.getLogger(MultiStagePointMatchClient.class);
                    log.info("generateRenderableCanvasIdPairs: entry");

                    final List<RenderableCanvasIdPairsForCollection> pairsList = new ArrayList<>();

                    while (stackWithZValuesIterator.hasNext()) {
                        generatePairsForBatch(multiProjectParameters,
                                              tilePairDerivationParameters,
                                              maxPairsPerStackBatch,
                                              stackWithZValuesIterator.next(),
                                              log,
                                              pairsList);
                    }

                    log.info("generateRenderableCanvasIdPairs: exit");

                    return pairsList.iterator();
                },
                true
                );
    }

    @SuppressWarnings("ExtractMethodRecommender")
    private static void generatePairsForBatch(final MultiProjectParameters multiProjectParameters,
                                              final TilePairDerivationParameters tilePairDerivationParameters,
                                              final int maxPairsPerStackBatch,
                                              final StackWithZValues stackWithZValues,
                                              final Logger log,
                                              final List<RenderableCanvasIdPairsForCollection> pairsList)
            throws IOException {

        final StackId stackId = stackWithZValues.getStackId();

        final TilePairClient.Parameters tilePairParameters =
                new TilePairClient.Parameters(multiProjectParameters.baseDataUrl,
                                              stackWithZValues,
                                              tilePairDerivationParameters,
                                              "/tmp/tile_pairs.json"); // ignored by in-memory client
        final InMemoryTilePairClient tilePairClient = new InMemoryTilePairClient(tilePairParameters);

        final MatchCollectionId matchCollectionId = multiProjectParameters.getMatchCollectionIdForStack(stackId);
        tilePairClient.overrideExcludePairsInMatchCollectionIfDefined(matchCollectionId);

        tilePairClient.deriveAndSaveSortedNeighborPairs();

        final RenderableCanvasIdPairs renderableCanvasIdPairs =
                new RenderableCanvasIdPairs(tilePairClient.getRenderParametersUrlTemplate(),
                                            tilePairClient.getNeighborPairs());

        final RenderableCanvasIdPairsForCollection pairsForCollection =
                new RenderableCanvasIdPairsForCollection(stackId,
                                                         renderableCanvasIdPairs,
                                                         matchCollectionId);

        for (final RenderableCanvasIdPairsForCollection batchPairs :
                pairsForCollection.splitPairsIntoBalancedBatches(maxPairsPerStackBatch)) {
            log.info("generatePairsForBatch: adding {}", batchPairs);
            pairsList.add(batchPairs);
        }
    }

    private static JavaRDD<RenderableCanvasIdPairsForCollection> repartitionPairsForBalancedProcessing(final JavaSparkContext sparkContext,
                                                                                                       final JavaRDD<RenderableCanvasIdPairsForCollection> rddCanvasIdPairsWithDrift) {
        // TODO: figure out how to repartition RDD without bringing all of the data back to the driver
        final List<RenderableCanvasIdPairsForCollection> batchedCanvasIdPairs =
                rddCanvasIdPairsWithDrift.collect();
        final JavaRDD<RenderableCanvasIdPairsForCollection> rddCanvasIdPairs =
                sparkContext.parallelize(batchedCanvasIdPairs);
        LOG.info("repartitionPairsForBalancedProcessing: process {} RenderableCanvasIdPairsForCollection objects across {} partitions",
                 batchedCanvasIdPairs.size(), rddCanvasIdPairs.getNumPartitions());
        return rddCanvasIdPairs;
    }

    private static JavaRDD<Integer> generateAndStoreCanvasMatches(final org.janelia.render.client.MultiStagePointMatchClient.Parameters multiStageParameters,
                                                                  final String baseDataUrl,
                                                                  final JavaRDD<RenderableCanvasIdPairsForCollection> rddCanvasIdPairs) {
        return rddCanvasIdPairs.mapPartitionsWithIndex(
                (Function2<Integer, Iterator<RenderableCanvasIdPairsForCollection>, Iterator<Integer>>) (partitionIndex, pairIterator) -> {

                    LogUtilities.setupExecutorLog4j("partition " + partitionIndex);
                    final Logger log = LoggerFactory.getLogger(MultiStagePointMatchClient.class);
                    log.info("generateAndStoreCanvasMatches: entry");

                    final org.janelia.render.client.MultiStagePointMatchClient localClient =
                            new org.janelia.render.client.MultiStagePointMatchClient(multiStageParameters);

                    final List<Integer> savedMatchPairCountList = new ArrayList<>();

                    RenderDataClient matchDataClient = null;
                    while (pairIterator.hasNext()) {

                        final RenderableCanvasIdPairsForCollection pairsForCollection = pairIterator.next();
                        final MatchCollectionId collectionId = pairsForCollection.getMatchCollectionId();

                        log.info("generateAndStoreCanvasMatches: processing {}", pairsForCollection);

                        if (pairsForCollection.size() < 1) {
                            log.info("generateAndStoreCanvasMatches: no pairs to process, moving on");
                            continue;
                        }

                        if (matchDataClient == null) {
                            matchDataClient = new RenderDataClient(baseDataUrl,
                                                                   collectionId.getOwner(),
                                                                   collectionId.getName());
                        } else {
                            // reuse as much of existing client resources as possible by building a derived client
                            matchDataClient = matchDataClient.buildClient(collectionId.getOwner(),
                                                                          collectionId.getName());
                        }

                        final List<CanvasMatches> matchList =
                                localClient.generateMatchesForPairs(pairsForCollection.getPairs(),
                                                                    baseDataUrl);
                        final List<CanvasMatches> nonEmptyMatchesList =
                                org.janelia.render.client.MultiStagePointMatchClient.storeMatches(matchList,
                                                                                                  matchDataClient);

                        final int percentSaved =
                                (int) ((nonEmptyMatchesList.size() / (double) pairsForCollection.size()) * 100);

                        log.info("generateAndStoreCanvasMatches: saved {} matches ({} %) for {}",
                                 nonEmptyMatchesList.size(), percentSaved, pairsForCollection);

                        savedMatchPairCountList.add(nonEmptyMatchesList.size());
                    }

                    log.info("generateAndStoreCanvasMatches: exit");

                    return savedMatchPairCountList.iterator();
                },
                true
        );
    }

    private static void clearDataLoaders(final JavaSparkContext sparkContext) {

        final List<Integer> partitionRange = IntStream.rangeClosed(0, sparkContext.defaultParallelism())
                .boxed().collect(Collectors.toList());
        final JavaRDD<Integer> rddPartitionRange = sparkContext.parallelize(partitionRange);

        LOG.info("clearDataLoaders: entry, clear loaders across {} partitions", rddPartitionRange.getNumPartitions());

        final JavaRDD<Integer> rddClearedCacheCounts = rddPartitionRange.mapPartitionsWithIndex(
                (Function2<Integer, Iterator<Integer>, Iterator<Integer>>) (partitionIndex, pairIterator) -> {
                    LogUtilities.setupExecutorLog4j("partition " + partitionIndex);
                    CanvasDataCache.clearAllSharedDataCaches();
                    return Collections.singletonList(1).iterator();
                },
                true
        );
        final int totalCleared = rddClearedCacheCounts.collect().stream().reduce(0, Integer::sum);

        LOG.info("clearDataLoaders: exit, cleared {} data caches", totalCleared);
    }

    private static final Logger LOG = LoggerFactory.getLogger(MultiStagePointMatchClient.class);
}

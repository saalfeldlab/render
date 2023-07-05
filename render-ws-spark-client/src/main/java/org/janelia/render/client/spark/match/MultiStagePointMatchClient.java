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
import org.janelia.alignment.match.parameters.FeatureStorageParameters;
import org.janelia.alignment.match.parameters.MatchRunParameters;
import org.janelia.alignment.match.parameters.TilePairDerivationParameters;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.TilePairClient;
import org.janelia.render.client.match.InMemoryTilePairClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.StackIdWithZParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for generating and storing SIFT point matches for a specified set of canvas (e.g. tile) pairs.
 *
 * @author Eric Trautman
 */
public class MultiStagePointMatchClient
        implements Serializable {

    public static class Parameters extends CommandLineParameters {

        // NOTE: did not use RenderWebServiceParameters delegate because wanted to have slightly different descriptions
        @Parameter(
                names = "--baseDataUrl",
                description = "Base web service URL for data (e.g. http://host[:port]/render-ws/v1)",
                required = true)
        public String baseDataUrl;

        @Parameter(
                names = "--owner",
                description = "Owner for all stacks and match collections",
                required = true)
        public String owner;

        @Parameter(
                names = "--project",
                description = "Project for all tiles (or first project if processing a multi-project run)",
                required = true)
        public String project;

        @Parameter(
                names = "--matchCollection",
                description = "Explicit collection in which to store matches (omit to use stack project derived names)")
        public String matchCollection;

        @ParametersDelegate
        public StackIdWithZParameters stackIdWithZ = new StackIdWithZParameters();

        @Parameter(
                names = "--matchRunJson",
                description = "JSON file where array of match run parameters are defined",
                required = true)
        public String matchRunJson;

        @Parameter(
                names = "--maxPairsPerStackBatch",
                description = "Maximum number of pairs to include in stack-based batches")
        public int maxPairsPerStackBatch = 100;

        @Parameter(
                names = { "--maxFeatureSourceCacheGb" },
                description = "Maximum number of gigabytes of source image and mask data to cache " +
                              "(note: 15000x15000 Fly EM mask is 500MB while 6250x4000 mask is 25MB)"
        )
        public Integer maxFeatureSourceCacheGb = 2;

        @Parameter(
                names = { "--gdMaxPeakCacheGb" },
                description = "Maximum number of gigabytes of peaks to cache")
        public Integer maxPeakCacheGb = 2;

        public FeatureStorageParameters getFeatureStorageParameters() {
            final FeatureStorageParameters p = new FeatureStorageParameters();
            p.maxFeatureSourceCacheGb = this.maxFeatureSourceCacheGb;
            return p;
        }

        public MatchCollectionId getMatchCollectionId() {
            MatchCollectionId matchCollectionId = null;
            if (matchCollection != null) {
                matchCollectionId = new MatchCollectionId(owner, matchCollection);
            }
            return matchCollectionId;
        }
    }


    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final MultiStagePointMatchClient client = new MultiStagePointMatchClient(parameters);
                client.run();

            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;

    private MultiStagePointMatchClient(final Parameters parameters) throws IllegalArgumentException {
        this.parameters = parameters;
    }

    public void run() throws IOException {

        final SparkConf conf = new SparkConf().setAppName("MultiStagePointMatchClient");

        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            final String sparkAppId = sparkContext.getConf().getAppId();
            LOG.info("run: appId is {}", sparkAppId);
            generatePairsAndMatches(sparkContext);
        }
    }

    public void generatePairsAndMatches(final JavaSparkContext sparkContext)
            throws IOException {

        LOG.info("generatePairsAndMatches: entry");

        final RenderDataClient renderDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                                       parameters.owner,
                                                                       parameters.project);

        final List<StackWithZValues> stackWithZValuesList = parameters.stackIdWithZ.getStackWithZList(renderDataClient);
        if (stackWithZValuesList.size() == 0) {
            throw new IllegalArgumentException("no stack z-layers match parameters");
        }

        for (final MatchRunParameters matchRunParameters : MatchRunParameters.fromJsonArrayFile(parameters.matchRunJson)) {
            generatePairsAndMatchesForRun(sparkContext,
                                          stackWithZValuesList,
                                          matchRunParameters);
            clearDataLoaders(sparkContext);
        }

        LOG.info("generatePairsAndMatches: exit");
    }

    public void generatePairsAndMatchesForRun(final JavaSparkContext sparkContext,
                                              final List<StackWithZValues> stackWithZValuesList,
                                              final MatchRunParameters matchRunParameters) {

        LOG.info("generatePairsAndMatchesForRun: entry, {} stackWithZValuesList items, matchRunParameters={}",
                 stackWithZValuesList.size(), matchRunParameters.toJson());

        final JavaRDD<RenderableCanvasIdPairsForCollection> rddCanvasIdPairsWithDrift =
                generateRenderableCanvasIdPairs(sparkContext,
                                                parameters.baseDataUrl,
                                                stackWithZValuesList,
                                                parameters.getMatchCollectionId(),
                                                matchRunParameters.getTilePairDerivationParameters(),
                                                parameters.maxPairsPerStackBatch);

        final JavaRDD<RenderableCanvasIdPairsForCollection> rddCanvasIdPairs =
                repartitionPairsForBalancedProcessing(sparkContext, rddCanvasIdPairsWithDrift);

        final org.janelia.render.client.MultiStagePointMatchClient.Parameters multiStageParameters =
                new org.janelia.render.client.MultiStagePointMatchClient.Parameters(
                        parameters.getFeatureStorageParameters(),
                        parameters.maxPeakCacheGb,
                        matchRunParameters.getMatchStageParametersList());

        final JavaRDD<Integer> rddSavedMatchPairCounts =
                generateAndStoreCanvasMatches(multiStageParameters,
                                              parameters.baseDataUrl,
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

    public JavaRDD<RenderableCanvasIdPairsForCollection> generateRenderableCanvasIdPairs(final JavaSparkContext sparkContext,
                                                                                         final String baseDataUrl,
                                                                                         final List<StackWithZValues> stackWithZValuesList,
                                                                                         final MatchCollectionId explicitMatchCollectionId,
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

                    StackWithZValues stackWithZValues;
                    MatchCollectionId matchCollectionId = explicitMatchCollectionId;
                    while (stackWithZValuesIterator.hasNext()) {

                        stackWithZValues = stackWithZValuesIterator.next();

                        final TilePairClient.Parameters tilePairParameters =
                                new TilePairClient.Parameters(baseDataUrl,
                                                              stackWithZValues,
                                                              tilePairDerivationParameters,
                                                              "/tmp/tile_pairs.json"); // ignored by in-memory client
                        final InMemoryTilePairClient tilePairClient = new InMemoryTilePairClient(tilePairParameters);

                        // derive match collection id from stack owner and project if it is not explicitly specified
                        if (explicitMatchCollectionId == null) {
                            final StackId stackId = stackWithZValues.getStackId();
                            matchCollectionId = new MatchCollectionId(stackId.getOwner(),
                                                                      stackId.getProject() + "_v1");
                            tilePairClient.overrideExcludePairsInMatchCollectionIfDefined(matchCollectionId);
                        }

                        tilePairClient.deriveAndSaveSortedNeighborPairs();

                        final RenderableCanvasIdPairs renderableCanvasIdPairs =
                                new RenderableCanvasIdPairs(tilePairClient.getRenderParametersUrlTemplate(),
                                                            tilePairClient.getNeighborPairs());


                        final RenderableCanvasIdPairsForCollection pairsForCollection =
                                new RenderableCanvasIdPairsForCollection(stackWithZValues.getStackId(),
                                                                         renderableCanvasIdPairs,
                                                                         matchCollectionId);

                        for (final RenderableCanvasIdPairsForCollection batchPairs :
                                pairsForCollection.splitPairsIntoBalancedBatches(maxPairsPerStackBatch)) {
                            log.info("generateRenderableCanvasIdPairs: adding {}", batchPairs);
                            pairsList.add(batchPairs);
                        }
                    }

                    log.info("generateRenderableCanvasIdPairs: exit");

                    return pairsList.iterator();

                },
                true
                );
    }

    public static JavaRDD<RenderableCanvasIdPairsForCollection> repartitionPairsForBalancedProcessing(final JavaSparkContext sparkContext,
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

    public static JavaRDD<Integer> generateAndStoreCanvasMatches(final org.janelia.render.client.MultiStagePointMatchClient.Parameters multiStageParameters,
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

    public static void clearDataLoaders(final JavaSparkContext sparkContext) {

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

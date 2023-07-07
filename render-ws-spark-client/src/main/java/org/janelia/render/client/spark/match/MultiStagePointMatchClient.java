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
import org.janelia.alignment.match.parameters.MatchRunParameters;
import org.janelia.alignment.match.parameters.TilePairDerivationParameters;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.TilePairClient;
import org.janelia.render.client.match.InMemoryTilePairClient;
import org.janelia.render.client.parameter.AlignmentPipelineParameters;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MatchCommonParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for generating and storing SIFT point matches.
 *
 * @author Eric Trautman
 */
public class MultiStagePointMatchClient
        implements Serializable {

    public static class Parameters extends CommandLineParameters {
        @ParametersDelegate
        public MultiProjectParameters multiProject = new MultiProjectParameters();
        @ParametersDelegate
        public MatchCommonParameters matchCommon = new MatchCommonParameters();
        @Parameter(
                names = "--matchRunJson",
                description = "JSON file where array of match run parameters are defined",
                required = true)
        public String matchRunJson;

        /** @return client specific parameters populated from specified alignment pipeline parameters. */
        public static Parameters fromPipeline(final AlignmentPipelineParameters alignmentPipelineParameters) {
            final Parameters derivedParameters = new Parameters();
            derivedParameters.multiProject = alignmentPipelineParameters.getMultiProject();
            derivedParameters.matchCommon = alignmentPipelineParameters.getMatchCommon();
            // NOTE: matchRunParameters should/will be loaded from alignmentPipelineParameters directly
            //       instead of from matchRunJson file
            return derivedParameters;
        }

    }


    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                final MultiStagePointMatchClient client = new MultiStagePointMatchClient(parameters);
                client.run();

            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;

    public MultiStagePointMatchClient(final Parameters parameters) throws IllegalArgumentException {
        LOG.info("init: parameters={}", parameters);
        this.parameters = parameters;
    }

    public void run() throws IOException {

        final SparkConf conf = new SparkConf().setAppName("MultiStagePointMatchClient");

        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            final String sparkAppId = sparkContext.getConf().getAppId();
            LOG.info("run: appId is {}", sparkAppId);
            runWithContext(sparkContext);
        }
    }

    public void runWithContext(final JavaSparkContext sparkContext)
            throws IOException {

        LOG.info("runWithContext: entry");

        final RenderDataClient renderDataClient = new RenderDataClient(parameters.multiProject.baseDataUrl,
                                                                       parameters.multiProject.owner,
                                                                       parameters.multiProject.project);

        final List<StackWithZValues> stackWithZValuesList = parameters.multiProject.stackIdWithZ.getStackWithZList(renderDataClient);
        if (stackWithZValuesList.size() == 0) {
            throw new IllegalArgumentException("no stack z-layers match parameters");
        }

        generatePairsAndMatchesForRunList(sparkContext,
                                          stackWithZValuesList,
                                          MatchRunParameters.fromJsonArrayFile(parameters.matchRunJson));

        LOG.info("runWithContext: exit");
    }

    public void generatePairsAndMatchesForRunList(final JavaSparkContext sparkContext,
                                                  final List<StackWithZValues> stackWithZValuesList,
                                                  final List<MatchRunParameters> matchRunList) {
        for (final MatchRunParameters matchRunParameters : matchRunList) {
            generatePairsAndMatchesForRun(sparkContext,
                                          stackWithZValuesList,
                                          matchRunParameters);
            clearDataLoaders(sparkContext);
        }
    }

    public void generatePairsAndMatchesForRun(final JavaSparkContext sparkContext,
                                              final List<StackWithZValues> stackWithZValuesList,
                                              final MatchRunParameters matchRunParameters) {

        LOG.info("generatePairsAndMatchesForRun: entry, {} stackWithZValuesList items, matchRunParameters={}",
                 stackWithZValuesList.size(), matchRunParameters.toJson());

        final JavaRDD<RenderableCanvasIdPairsForCollection> rddCanvasIdPairsWithDrift =
                generateRenderableCanvasIdPairs(sparkContext,
                                                parameters.multiProject.baseDataUrl,
                                                stackWithZValuesList,
                                                parameters.multiProject.getMatchCollectionId(),
                                                matchRunParameters.getTilePairDerivationParameters(),
                                                parameters.matchCommon.maxPairsPerStackBatch);

        final JavaRDD<RenderableCanvasIdPairsForCollection> rddCanvasIdPairs =
                repartitionPairsForBalancedProcessing(sparkContext, rddCanvasIdPairsWithDrift);

        final org.janelia.render.client.MultiStagePointMatchClient.Parameters multiStageParameters =
                new org.janelia.render.client.MultiStagePointMatchClient.Parameters(
                        parameters.matchCommon.featureStorage,
                        parameters.matchCommon.maxPeakCacheGb,
                        matchRunParameters.getMatchStageParametersList());

        final JavaRDD<Integer> rddSavedMatchPairCounts =
                generateAndStoreCanvasMatches(multiStageParameters,
                                              parameters.multiProject.baseDataUrl,
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

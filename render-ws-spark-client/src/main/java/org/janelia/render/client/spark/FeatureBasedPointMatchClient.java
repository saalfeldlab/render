package org.janelia.render.client.spark;

import com.beust.jcommander.Parameter;

import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import mpicbg.imagefeatures.FloatArray2DSIFT;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function2;
import org.janelia.alignment.match.CanvasMatchFilter;
import org.janelia.alignment.match.CanvasFeatureMatchResult;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.CanvasSiftFeatureExtractor;
import org.janelia.alignment.match.CanvasSiftFeatureMatcher;
import org.janelia.alignment.match.CanvasSurfFeatureExtractor;
import org.janelia.alignment.match.CanvasSurfFeatureMatcher;
import org.janelia.alignment.match.Matches;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.MatchDataClientParameters;
import org.janelia.render.client.spark.cache.CanvasDataCache;
import org.janelia.render.client.spark.cache.CanvasDataLoader;
import org.janelia.render.client.spark.cache.CanvasSiftFeatureListLoader;
import org.janelia.render.client.spark.cache.CanvasSurfFeatureListLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import boofcv.abst.feature.detect.interest.ConfigFastHessian;
import boofcv.struct.image.GrayF32;

/**
 * Spark client for generating and storing feature based (SIFT or SURF) point matches
 * for a specified set of canvas (e.g. tile) pairs.
 *
 * @author Eric Trautman
 */
public class FeatureBasedPointMatchClient
        implements Serializable {

    @SuppressWarnings("ALL")
    public static class Parameters extends MatchDataClientParameters {

        // NOTE: --baseDataUrl, --owner, and --collection parameters defined in MatchDataClientParameters

        @Parameter(names = "--pairJson", description = "JSON file where tile pairs are stored (.json, .gz, or .zip)", required = true)
        private String pairJson;

        @Parameter(
                names = "--renderWithFilter",
                description = "Render tiles using a filter for intensity correction",
                required = false,
                arity = 1)
        private boolean renderWithFilter = true;

        @Parameter(
                names = "--renderWithoutMask",
                description = "Render tiles without a mask",
                required = false,
                arity = 1)
        private boolean renderWithoutMask = true;

        @Parameter(names = "--renderFullScaleWidth", description = "Full scale width for all rendered tiles", required = false)
        private Integer renderFullScaleWidth;

        @Parameter(names = "--renderFullScaleHeight", description = "Full scale height for all rendered tiles", required = false)
        private Integer renderFullScaleHeight;

        @Parameter(names = "--renderScale", description = "Render tiles at this scale", required = false)
        private Double renderScale = 1.0;

        @Parameter(
                names = "--fillWithNoise",
                description = "Fill each canvas image with noise before rendering to improve point match derivation",
                required = false,
                arity = 1)
        private boolean fillWithNoise = true;

        @Parameter(names = "--SIFTfdSize", description = "SIFT feature descriptor size: how many samples per row and column", required = false)
        private Integer fdSize = 8;

        @Parameter(names = "--SIFTminScale", description = "SIFT minimum scale: minSize * minScale < size < maxSize * maxScale", required = false)
        private Double minScale = 0.5;

        @Parameter(names = "--SIFTmaxScale", description = "SIFT maximum scale: minSize * minScale < size < maxSize * maxScale", required = false)
        private Double maxScale = 0.85;

        @Parameter(names = "--SIFTsteps", description = "SIFT steps per scale octave", required = false)
        private Integer steps = 3;

        @Parameter(names = "--SIFTmatchRod", description = "Ratio of distances for SIFT matches", required = false)
        private Float matchRod = 0.92f;

        @Parameter(names = "--matchMaxEpsilon", description = "Minimal allowed transfer error for matches", required = false)
        private Float matchMaxEpsilon = 20.0f;

        @Parameter(names = "--matchMinInlierRatio", description = "Minimal ratio of inliers to candidates for matches", required = false)
        private Float matchMinInlierRatio = 0.0f;

        @Parameter(names = "--matchMinNumInliers", description = "Minimal absolute number of inliers for matches", required = false)
        private Integer matchMinNumInliers = 4;

        @Parameter(names = "--matchMaxNumInliers", description = "Maximum number of inliers for matches", required = false)
        private Integer matchMaxNumInliers;

        @Parameter(names = "--maxFeatureCacheGb", description = "Maximum number of gigabytes of features to cache", required = false)
        private Integer maxFeatureCacheGb = 2;

        @Parameter(
                names = "--useSURF",
                description = "Use SURF feature detection (instead of SIFT)",
                required = false,
                arity = 0)
        private boolean useSurf = false;

        @Parameter(names = "--SURFdetectThreshold", description = "SURF minimum feature intensity", required = false)
        private Float surfDetectThreshold = CanvasSurfFeatureExtractor.DEFAULT_CONFIG.detectThreshold;

        @Parameter(names = "--SURFextractRadius", description = "SURF radius used for non-max-suppression", required = false)
        private Integer surfExtractRadius = CanvasSurfFeatureExtractor.DEFAULT_CONFIG.extractRadius;

        @Parameter(names = "--SURFmaxFeaturesPerScale", description = "Number of SURF features to find (less than 0 indicates all features)", required = false)
        private Integer surfMaxFeaturesPerScale = CanvasSurfFeatureExtractor.DEFAULT_CONFIG.maxFeaturesPerScale;

        @Parameter(names = "--SURFinitialSampleSize", description = "How often pixels are sampled in the first octave for SURF", required = false)
        private Integer surfInitialSampleSize = CanvasSurfFeatureExtractor.DEFAULT_CONFIG.initialSampleSize;

        @Parameter(names = "--SURFinitialSize", description = "How often pixels are sampled in the first octave for SURF", required = false)
        private Integer surfInitialSize = CanvasSurfFeatureExtractor.DEFAULT_CONFIG.initialSize;

        @Parameter(names = "--SURFnumberScalesPerOctave", description = "SURF number of scales per octave", required = false)
        private Integer surfNumberScalesPerOctave = CanvasSurfFeatureExtractor.DEFAULT_CONFIG.numberScalesPerOctave;

        @Parameter(names = "--SURFnumberOfOctaves", description = "SURF number of octaves", required = false)
        private Integer surfNumberOfOctaves = CanvasSurfFeatureExtractor.DEFAULT_CONFIG.numberOfOctaves;

        public ConfigFastHessian getSurfConfig() {
            return new ConfigFastHessian(surfDetectThreshold,
                                         surfExtractRadius,
                                         surfMaxFeaturesPerScale,
                                         surfInitialSampleSize,
                                         surfInitialSize,
                                         surfNumberScalesPerOctave,
                                         surfNumberOfOctaves);
        }

    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args, FeatureBasedPointMatchClient.class);

                LOG.info("runClient: entry, parameters={}", parameters);

                final FeatureBasedPointMatchClient client = new FeatureBasedPointMatchClient(parameters);
                client.run();

            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;

    public FeatureBasedPointMatchClient(final Parameters parameters) throws IllegalArgumentException {
        this.parameters = parameters;
    }

    public void run() throws IOException, URISyntaxException {

        final String appName = parameters.useSurf ? "SURF-match-generator" : "SIFT-match-generator";
        final SparkConf conf = new SparkConf().setAppName(appName);
        final JavaSparkContext sparkContext = new JavaSparkContext(conf);

        final String sparkAppId = sparkContext.getConf().getAppId();
        final String executorsJson = LogUtilities.getExecutorsApiJson(sparkAppId);

        LOG.info("run: appId is {}, executors data is {}", sparkAppId, executorsJson);

        final RenderableCanvasIdPairs renderableCanvasIdPairs =
                RenderableCanvasIdPairsUtilities.load(parameters.pairJson);

        final String renderParametersUrlTemplateForRun =
                RenderableCanvasIdPairsUtilities.getRenderParametersUrlTemplateForRun(
                        renderableCanvasIdPairs,
                        parameters.baseDataUrl,
                        parameters.renderFullScaleWidth,
                        parameters.renderFullScaleHeight,
                        parameters.renderScale,
                        parameters.renderWithFilter,
                        parameters.renderWithoutMask);


        final CanvasDataLoader canvasDataLoader;
        if (parameters.useSurf) {
            canvasDataLoader =
                    new CanvasSurfFeatureListLoader(renderParametersUrlTemplateForRun,
                                                    new CanvasSurfFeatureExtractor<>(parameters.getSurfConfig(),
                                                                                     parameters.fillWithNoise,
                                                                                     GrayF32.class));
        } else {
            final FloatArray2DSIFT.Param siftParameters = new FloatArray2DSIFT.Param();
            siftParameters.fdSize = parameters.fdSize;
            siftParameters.steps = parameters.steps;
            canvasDataLoader = new CanvasSiftFeatureListLoader(
                    renderParametersUrlTemplateForRun,
                    new CanvasSiftFeatureExtractor(siftParameters,
                                                   parameters.minScale,
                                                   parameters.maxScale,
                                                   parameters.fillWithNoise));
        }

        final CanvasMatchFilter matchFilter = new CanvasMatchFilter(parameters.matchMaxEpsilon,
                                                                    parameters.matchMinInlierRatio,
                                                                    parameters.matchMinNumInliers,
                                                                    parameters.matchMaxNumInliers,
                                                                    true);

        final long cacheMaxKilobytes = parameters.maxFeatureCacheGb * 1000000;

        final JavaRDD<OrderedCanvasIdPair> rddCanvasIdPairs =
                sparkContext.parallelize(renderableCanvasIdPairs.getNeighborPairs());

        final JavaRDD<CanvasMatches> rddMatches = rddCanvasIdPairs.mapPartitionsWithIndex(
                new Function2<Integer, Iterator<OrderedCanvasIdPair>, Iterator<CanvasMatches>>() {

                    @Override
                    public Iterator<CanvasMatches> call(final Integer partitionIndex,
                                                        final Iterator<OrderedCanvasIdPair> pairIterator)
                            throws Exception {

                        LogUtilities.setupExecutorLog4j("partition " + partitionIndex);

                        final Logger log = LoggerFactory.getLogger(FeatureBasedPointMatchClient.class);

                        final CanvasDataCache dataCache = CanvasDataCache.getSharedCache(cacheMaxKilobytes,
                                                                                         canvasDataLoader);

                        final CanvasSurfFeatureMatcher surfFeatureMatcher = new CanvasSurfFeatureMatcher();
                        final CanvasSiftFeatureMatcher siftFeatureMatcher =
                                new CanvasSiftFeatureMatcher(parameters.matchRod);

                        final List<CanvasMatches> matchList = new ArrayList<>();
                        int pairCount = 0;

                        OrderedCanvasIdPair pair;
                        CanvasId p;
                        CanvasId q;
                        CanvasFeatureMatchResult matchResult;
                        Matches inlierMatches;
                        while (pairIterator.hasNext()) {

                            pair = pairIterator.next();
                            pairCount++;

                            p = pair.getP();
                            q = pair.getQ();

                            log.info("derive matches between {} and {}", p, q);

                            if (parameters.useSurf) {
                                matchResult = surfFeatureMatcher.deriveMatchResult(dataCache.getSurfFeatureList(p),
                                                                                   dataCache.getSurfFeatureList(q),
                                                                                   matchFilter);
                            } else {
                                matchResult = siftFeatureMatcher.deriveMatchResult(dataCache.getFeatureList(p),
                                                                                   dataCache.getFeatureList(q),
                                                                                   matchFilter);
                            }

                            inlierMatches = matchResult.getInlierMatches(parameters.renderScale);

                            if (inlierMatches.getWs().length > 0) {
                                matchList.add(new CanvasMatches(p.getGroupId(), p.getId(),
                                                                q.getGroupId(), q.getId(),
                                                                inlierMatches));
                            }
                        }

                        log.info("rddMatches: derived matches for {} out of {} pairs, cache stats are {}",
                                 matchList.size(), pairCount, dataCache.stats());

                        return matchList.iterator();
                    }
                },
                true
        );

        final JavaRDD<Integer> rddSavedMatchPairCounts = rddMatches.mapPartitionsWithIndex(
                new MatchStorageFunction(parameters.baseDataUrl,
                                         parameters.owner,
                                         parameters.collection),
                true
        );

        final int numPartitions = rddSavedMatchPairCounts.getNumPartitions();

        LOG.info("run: {} partitions, debug string is: \n{}",
                 numPartitions, rddSavedMatchPairCounts.toDebugString());

        final List<Integer> matchPairCountList = rddSavedMatchPairCounts.collect();
        long total = 0;
        for (final Integer matchCount : matchPairCountList) {
            total += matchCount;
        }

        LOG.info("run: collected stats");
        LOG.info("run: saved {} match pairs on {} partitions", total, matchPairCountList.size());

        sparkContext.stop();

    }

    private static final Logger LOG = LoggerFactory.getLogger(FeatureBasedPointMatchClient.class);
}

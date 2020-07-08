package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import mpicbg.imglib.algorithm.scalespace.DifferenceOfGaussianPeak;
import mpicbg.imglib.type.numeric.real.FloatType;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;

import org.janelia.alignment.match.CanvasFeatureExtractor;
import org.janelia.alignment.match.CanvasFeatureMatcher;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasIdWithRenderContext;
import org.janelia.alignment.match.CanvasMatchResult;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.CanvasPeakExtractor;
import org.janelia.alignment.match.CanvasPeakMatcher;
import org.janelia.alignment.match.CanvasRenderParametersUrlTemplate;
import org.janelia.alignment.match.MatchFilter;
import org.janelia.alignment.match.Matches;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.PointMatchQualityStats;
import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.janelia.alignment.match.parameters.FeatureExtractionParameters;
import org.janelia.alignment.match.parameters.FeatureRenderClipParameters;
import org.janelia.alignment.match.parameters.FeatureRenderParameters;
import org.janelia.alignment.match.parameters.GeometricDescriptorAndMatchFilterParameters;
import org.janelia.alignment.match.parameters.GeometricDescriptorParameters;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.janelia.alignment.match.parameters.MatchStageParameters;
import org.janelia.alignment.util.FileUtil;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.cache.CachedCanvasFeatures;
import org.janelia.render.client.cache.CachedCanvasPeaks;
import org.janelia.render.client.cache.CanvasDataCache;
import org.janelia.render.client.cache.CanvasFeatureListLoader;
import org.janelia.render.client.cache.CanvasPeakListLoader;
import org.janelia.render.client.cache.MultiStageCanvasDataLoader;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.FeatureStorageParameters;
import org.janelia.render.client.parameter.MatchWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.janelia.alignment.match.MatchFilter.FilterType.CONSENSUS_SETS;

/**
 * Java client for generating and storing SIFT point matches for a specified set of canvas (e.g. tile) pairs.
 *
 * @author Eric Trautman
 */
public class MultiStagePointMatchClient
        implements Serializable {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        MatchWebServiceParameters matchClient = new MatchWebServiceParameters();

        @ParametersDelegate
        FeatureStorageParameters featureStorage = new FeatureStorageParameters();

        @Parameter(
                names = { "--gdMaxPeakCacheGb" },
                description = "Maximum number of gigabytes of peaks to cache")
        public Integer maxPeakCacheGb = 2;

        @Parameter(
                names = "--cacheFullScaleSourcePixels",
                description = "Indicates that full scale source images should also be cached " +
                              "for dynamically down-sampled images.  This is useful for stacks without " +
                              "down-sampled mipmaps since it avoids reloading the full scale images when " +
                              "different scales of the same image are rendered.  " +
                              "This should not be used for stacks with mipmaps since that would waste cache space.",
                arity = 0)
        public boolean cacheFullScaleSourcePixels = false;

        @Parameter(
                names = "--failedPairsDir",
                description = "Write failed pairs (ones that did not have matches) to a JSON file in this directory",
                arity = 1)
        public String failedPairsDir;

        @Parameter(
                names = "--stageJson",
                description = "JSON file where stage match parameters are defined",
                required = true)
        public String stageJson;

        @Parameter(
                names = "--pairJson",
                description = "JSON file where tile pairs are stored (.json, .gz, or .zip)",
                required = true,
                order = 5)
        public List<String> pairJson;
    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final MultiStagePointMatchClient client = new MultiStagePointMatchClient(parameters);
                for (final String pairJsonFileName : parameters.pairJson) {
                    client.generateMatchesForPairFile(pairJsonFileName);
                }
                client.logStats();

            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;
    private final RenderDataClient matchStorageClient;
    private final ImageProcessorCache sourceImageProcessorCache;
    private final Map<String, MatchPairCounts> stageNameToPairCountsMap;

    private CanvasIdWithRenderContext pFeatureContextCanvasId;
    private CanvasIdWithRenderContext qFeatureContextCanvasId;
    private CanvasIdWithRenderContext pPeakContextCanvasId;
    private CanvasIdWithRenderContext qPeakContextCanvasId;

    MultiStagePointMatchClient(final Parameters parameters) throws IllegalArgumentException {
        this.parameters = parameters;
        this.matchStorageClient = new RenderDataClient(parameters.matchClient.baseDataUrl,
                                                       parameters.matchClient.owner,
                                                       parameters.matchClient.collection);

        final long maximumNumberOfCachedSourcePixels =
                parameters.featureStorage.maxFeatureSourceCacheGb * 1_000_000_000;
        sourceImageProcessorCache = new ImageProcessorCache(maximumNumberOfCachedSourcePixels,
                                                            true,
                                                            parameters.cacheFullScaleSourcePixels);

        this.stageNameToPairCountsMap = new HashMap<>();

        // make sure the failed pairs directory exists before we get started
        if (parameters.failedPairsDir != null) {
            FileUtil.ensureWritableDirectory(new File(parameters.failedPairsDir));
        }
    }

    private void generateMatchesForPairFile(final String pairJsonFileName)
            throws IOException {

        LOG.info("generateMatchesForPairFile: pairJsonFileName is {}", pairJsonFileName);

        final RenderableCanvasIdPairs renderableCanvasIdPairs = RenderableCanvasIdPairs.load(pairJsonFileName);

        final List<MatchStageParameters> stageParametersList =
                MatchStageParameters.fromJsonArrayFile(parameters.stageJson);

        if ((stageParametersList.size() > 1) && (parameters.featureStorage.rootFeatureDirectory != null)) {
            // CanvasFeatureList writeToStorage and readToStorage methods only support one storage location
            // for each CanvasId, so different renderings of the same canvas (for different match stages)
            // are not currently supported.
            throw new IllegalArgumentException(
                    "Stored features are not supported for runs with multiple stages.  " +
                    "Remove the --rootFeatureDirectory parameter or choose a --stageJson list with only one stage.");
        }

        for (final MatchStageParameters stageParameters : stageParametersList) {
            stageParameters.validateAndSetDefaults();
        }

        final List<StageResources> stageResourcesList = buildStageResourcesList(renderableCanvasIdPairs,
                                                                                parameters.matchClient.baseDataUrl,
                                                                                stageParametersList);

        final List<CanvasMatches> nonEmptyMatchesList =
                generateMatchesForPairs(renderableCanvasIdPairs,
                                        stageResourcesList,
                                        parameters.featureStorage);

        // if failed pairs directory is defined, write any failed pairs to a JSON file
        if ((parameters.failedPairsDir != null) &&
            (nonEmptyMatchesList.size() < renderableCanvasIdPairs.size())) {

            final Set<OrderedCanvasIdPair> nonEmptyMatchesSet = new HashSet<>(nonEmptyMatchesList.size());
            for (final CanvasMatches canvasMatches : nonEmptyMatchesList) {
                nonEmptyMatchesSet.add(new OrderedCanvasIdPair(new CanvasId(canvasMatches.getpGroupId(),
                                                                            canvasMatches.getpId()),
                                                               new CanvasId(canvasMatches.getqGroupId(),
                                                                            canvasMatches.getqId()),
                                                               null));
            }

            final List<OrderedCanvasIdPair>  failedPairsList = new ArrayList<>(renderableCanvasIdPairs.size());

            for (final OrderedCanvasIdPair pair : renderableCanvasIdPairs.getNeighborPairs()) {
                final CanvasId p = pair.getP();
                final CanvasId q = pair.getQ();
                final OrderedCanvasIdPair pairWithoutPosition = new OrderedCanvasIdPair(new CanvasId(p.getGroupId(),
                                                                                                     p.getId()),
                                                                                        new CanvasId(q.getGroupId(),
                                                                                                     q.getId()),
                                                                                        null);
                if (! nonEmptyMatchesSet.contains(pairWithoutPosition)) {
                    failedPairsList.add(pair);
                }
            }

            final File sourceJsonFile = new File(pairJsonFileName);
            final File failedPairsFile = new File(parameters.failedPairsDir, sourceJsonFile.getName());
            final RenderableCanvasIdPairs failedPairs =
                    new RenderableCanvasIdPairs(renderableCanvasIdPairs.getRenderParametersUrlTemplate(),
                                                failedPairsList);

            FileUtil.saveJsonFile(failedPairsFile.getAbsolutePath(), failedPairs);
        }
    }

    private List<CanvasMatches> generateMatchesForPairs(final RenderableCanvasIdPairs renderableCanvasIdPairs,
                                                        final List<StageResources> stageResourcesList,
                                                        final FeatureStorageParameters featureStorageParameters)
            throws IOException {

        final MultiStageCanvasDataLoader multiStageFeatureLoader =
                new MultiStageCanvasDataLoader(CachedCanvasFeatures.class);
        final MultiStageCanvasDataLoader multiStagePeakLoader =
                new MultiStageCanvasDataLoader(CachedCanvasPeaks.class);
        for (final StageResources stageResources : stageResourcesList) {
            multiStageFeatureLoader.addLoader(stageResources.featureLoaderName, stageResources.featureLoader);
            if (stageResources.stageParameters.hasGeometricDescriptorAndMatchFilterParameters()) {
                multiStagePeakLoader.addLoader(stageResources.peakLoaderName, stageResources.peakLoader);
            }
        }

        final long featureCacheMaxKilobytes = featureStorageParameters.maxFeatureCacheGb * 1_000_000;
        final CanvasDataCache featureDataCache = CanvasDataCache.getSharedCache(featureCacheMaxKilobytes,
                                                                                multiStageFeatureLoader);

        final CanvasDataCache peakDataCache;
        if (multiStagePeakLoader.getNumberOfStages() > 0) {
            final long peakCacheMaxKilobytes = parameters.maxPeakCacheGb * 1000000;
            peakDataCache = CanvasDataCache.getSharedCache(peakCacheMaxKilobytes,
                                                           multiStagePeakLoader);
        }  else {
            peakDataCache = null;
        }

        final List<CanvasMatches> matchList = new ArrayList<>();

        for (final OrderedCanvasIdPair pair : renderableCanvasIdPairs.getNeighborPairs()) {
            final CanvasId p = pair.getP();
            final CanvasId q = pair.getQ();

            this.pFeatureContextCanvasId = null;
            this.qFeatureContextCanvasId = null;
            this.pPeakContextCanvasId = null;
            this.qPeakContextCanvasId = null;

            for (final StageResources stageResources : stageResourcesList) {
                if (stageResources.stageParameters.exceedsMaxNeighborDistance(pair.getAbsoluteDeltaZ())) {
                    LOG.info("skipping stage {} for pair {} with absoluteDeltaZ {}",
                             stageResources.stageParameters.getStageName(), pair, pair.getAbsoluteDeltaZ());
                    break;
                }
                final List<CanvasMatches> pairMatchListForStage =
                        generateStageMatchesForPair(stageResources, featureDataCache, peakDataCache, p, q);
                if (pairMatchListForStage.size() > 0) {
                    matchList.addAll(pairMatchListForStage);
                    break;
                }
            }
        }

        final int pairCount = renderableCanvasIdPairs.size();

        LOG.info("generateMatchesForPairs: derived matches for {} out of {} pairs", matchList.size(), pairCount);
        LOG.info("generateMatchesForPairs: source cache stats are {}", sourceImageProcessorCache.getStats());
        LOG.info("generateMatchesForPairs: feature cache stats are {}", featureDataCache.stats());
        if (peakDataCache != null) {
            LOG.info("generateMatchesForPairs: peak cache stats are {}", peakDataCache.stats());
        }

        return storeMatches(matchList);
    }

    private List<CanvasMatches> generateStageMatchesForPair(final StageResources stageResources,
                                                            final CanvasDataCache featureDataCache,
                                                            final CanvasDataCache peakDataCache,
                                                            final CanvasId p,
                                                            final CanvasId q) {

        final List<CanvasMatches> pairMatchListForStage = new ArrayList<>();

        final String stageName = stageResources.stageParameters.getStageName();

        LOG.info("generateStageMatchesForPair: derive matches between {} and {} for stage {}", p, q, stageName);

        final MatchPairCounts pairCounts = stageNameToPairCountsMap.computeIfAbsent(stageName,
                                                                                    n -> new MatchPairCounts());

        pFeatureContextCanvasId = stageResources.getFeatureContextCanvasId(p,
                                                                           pFeatureContextCanvasId);
        qFeatureContextCanvasId = stageResources.getFeatureContextCanvasId(q,
                                                                           qFeatureContextCanvasId);

        final CachedCanvasFeatures pFeatures = featureDataCache.getCanvasFeatures(pFeatureContextCanvasId);
        final CachedCanvasFeatures qFeatures = featureDataCache.getCanvasFeatures(qFeatureContextCanvasId);

        final CanvasMatchResult matchResult = stageResources.featureMatcher.deriveMatchResult(pFeatures.getFeatureList(),
                                                                      qFeatures.getFeatureList());

        final double[] pClipOffsets = pFeatures.getClipOffsets();
        final double[] qClipOffsets = qFeatures.getClipOffsets();

        final MatchDerivationParameters featureMatchDerivation =
                stageResources.stageParameters.getFeatureMatchDerivation();

        if (stageResources.peakExtractor == null) {

            if (matchResult.getTotalNumberOfInliers() > 0) {

                final FeatureRenderParameters featureRender =
                        stageResources.stageParameters.getFeatureRender();
                final Double siftMinCoveragePercentage = featureMatchDerivation.matchMinCoveragePercentage;
                boolean keepMatches = true;

                if (siftMinCoveragePercentage != null) {
                    final PointMatchQualityStats siftQualityStats =
                            matchResult.calculateQualityStats(pFeatures.getRenderParameters(),
                                                              pFeatures.getMaskProcessor(),
                                                              qFeatures.getRenderParameters(),
                                                              qFeatures.getMaskProcessor(),
                                                              featureMatchDerivation.matchFullScaleCoverageRadius);
                    keepMatches = siftQualityStats.hasSufficientCoverage(siftMinCoveragePercentage);
                }

                if (keepMatches) {
                    // since no GD parameters were specified, simply save SIFT results
                    matchResult.addInlierMatchesToList(p.getGroupId(),
                                                       p.getId(),
                                                       q.getGroupId(),
                                                       q.getId(),
                                                       featureRender.renderScale,
                                                       pClipOffsets,
                                                       qClipOffsets,
                                                       pairMatchListForStage);
                    pairCounts.siftSaved++;
                } else {
                    LOG.info("generateStageMatchesForPair: dropping SIFT matches because coverage is insufficient");
                    pairCounts.siftPoorCoverage++;
                }

            } else {
                LOG.info("generateStageMatchesForPair: no SIFT matches to save");
                pairCounts.siftPoorQuantity++;
            }

        } else {

            // GD parameters have been specified
            appendGeometricMatchesIfNecessary(stageResources,
                                              pFeatures,
                                              qFeatures,
                                              peakDataCache,
                                              pairMatchListForStage,
                                              p,
                                              q,
                                              matchResult,
                                              pairCounts);
        }

        LOG.info("generateStageMatchesForPair: exit, returning list with {} element(s) for pair {} and {} in stage {}",
                 pairMatchListForStage.size(), p, q, stageName);

        return pairMatchListForStage;
    }

    private void appendGeometricMatchesIfNecessary(final StageResources stageResources,
                                                   final CachedCanvasFeatures pCanvasFeatures,
                                                   final CachedCanvasFeatures qCanvasFeatures,
                                                   final CanvasDataCache peakDataCache,
                                                   final List<CanvasMatches> pairMatchListForStage,
                                                   final CanvasId p,
                                                   final CanvasId q,
                                                   final CanvasMatchResult siftMatchResult,
                                                   final MatchPairCounts pairCounts) {

        final GeometricDescriptorAndMatchFilterParameters gdam =
                stageResources.stageParameters.getGeometricDescriptorAndMatch();

        final double siftFullScaleOverlapBlockRadius =
                stageResources.stageParameters.getFeatureMatchDerivation().matchFullScaleCoverageRadius;

        final PointMatchQualityStats siftQualityStats =
                siftMatchResult.calculateQualityStats(pCanvasFeatures.getRenderParameters(),
                                                      pCanvasFeatures.getMaskProcessor(),
                                                      qCanvasFeatures.getRenderParameters(),
                                                      qCanvasFeatures.getMaskProcessor(),
                                                      siftFullScaleOverlapBlockRadius);

        final double[] pClipOffsets = pCanvasFeatures.getClipOffsets();
        final double[] qClipOffsets = qCanvasFeatures.getClipOffsets();

        if (gdam.hasSufficiencyConstraints()) {

            // check SIFT results to see if GD is needed ...

            if (siftQualityStats.hasSufficientQuantity(gdam.minCombinedInliers)) {

                if (siftQualityStats.hasSufficientCoverage(gdam.minCombinedCoveragePercentage)) {

                    LOG.info("appendGeometricMatchesIfNecessary: saving {} SIFT matches and skipping Geometric process",
                             siftMatchResult.getTotalNumberOfInliers());
                    pairCounts.siftSaved++;

                    siftMatchResult.addInlierMatchesToList(p.getGroupId(),
                                                           p.getId(),
                                                           q.getGroupId(),
                                                           q.getId(),
                                                           pCanvasFeatures.getRenderParameters().getScale(),
                                                           pClipOffsets,
                                                           qClipOffsets,
                                                           pairMatchListForStage);

                } else {

                    LOG.info("appendGeometricMatchesIfNecessary: running Geometric process to improve coverage");

                    findGeometricDescriptorMatches(stageResources,
                                                   peakDataCache,
                                                   pairMatchListForStage,
                                                   p,
                                                   pCanvasFeatures,
                                                   q,
                                                   qCanvasFeatures,
                                                   siftMatchResult,
                                                   pairCounts);
                }

            } else if (gdam.runGeoRegardlessOfSiftResults) {

                LOG.info("appendGeometricMatchesIfNecessary: running Geometric process to improve quantity");

                findGeometricDescriptorMatches(stageResources,
                                               peakDataCache,
                                               pairMatchListForStage,
                                               p,
                                               pCanvasFeatures,
                                               q,
                                               qCanvasFeatures,
                                               siftMatchResult,
                                               pairCounts);

            } else {
                LOG.info("appendGeometricMatchesIfNecessary: dropping SIFT matches and skipping Geometric process because only {} matches were found",
                         siftMatchResult.getTotalNumberOfInliers());
                pairCounts.siftPoorQuantity++;
            }


        } else {

            LOG.info("appendGeometricMatchesIfNecessary: running Geometric process since sufficiency constraints are not defined");

            findGeometricDescriptorMatches(stageResources,
                                           peakDataCache,
                                           pairMatchListForStage,
                                           p,
                                           pCanvasFeatures,
                                           q,
                                           qCanvasFeatures,
                                           siftMatchResult,
                                           pairCounts);

        }
    }

    private void findGeometricDescriptorMatches(final StageResources stageResources,
                                                final CanvasDataCache peakDataCache,
                                                final List<CanvasMatches> pairMatchListForStage,
                                                final CanvasId p,
                                                final CachedCanvasFeatures pCanvasFeatures,
                                                final CanvasId q,
                                                final CachedCanvasFeatures qCanvasFeatures,
                                                final CanvasMatchResult siftMatchResult,
                                                final MatchPairCounts pairCounts) {

        final GeometricDescriptorAndMatchFilterParameters gdam =
                stageResources.stageParameters.getGeometricDescriptorAndMatch();
        final double siftFullScaleOverlapBlockRadius =
                stageResources.stageParameters.getFeatureMatchDerivation().matchFullScaleCoverageRadius;
        final CanvasPeakExtractor peakExtractor = stageResources.peakExtractor;

        final double siftRenderScale = pCanvasFeatures.getRenderParameters().getScale();
        final double[] pClipOffsets = pCanvasFeatures.getClipOffsets();
        final double[] qClipOffsets = qCanvasFeatures.getClipOffsets();

        final GeometricDescriptorParameters gdParameters = gdam.geometricDescriptorParameters;
        final double peakRenderScale = gdam.renderScale;

        pPeakContextCanvasId = stageResources.getPeakContextCanvasId(p,
                                                                     pPeakContextCanvasId);
        qPeakContextCanvasId = stageResources.getPeakContextCanvasId(q,
                                                                     qPeakContextCanvasId);

        final CachedCanvasPeaks pCanvasPeaks = peakDataCache.getCanvasPeaks(pPeakContextCanvasId);
        final CachedCanvasPeaks qCanvasPeaks = peakDataCache.getCanvasPeaks(qPeakContextCanvasId);

        final List<DifferenceOfGaussianPeak<FloatType>> pCanvasPeakList = pCanvasPeaks.getPeakList();
        final List<DifferenceOfGaussianPeak<FloatType>> qCanvasPeakList = qCanvasPeaks.getPeakList();

        final List<PointMatch> siftScaledInliers = siftMatchResult.getInlierPointMatchList();

        CanvasMatches combinedCanvasMatches = null;
        if (siftScaledInliers.size() > 0) {

            final List<Point> pInlierPoints = new ArrayList<>();
            final List<Point> qInlierPoints = new ArrayList<>();
            PointMatch.sourcePoints(siftScaledInliers, pInlierPoints);
            PointMatch.targetPoints(siftScaledInliers, qInlierPoints);

            peakExtractor.filterPeaksByInliers(pCanvasPeakList, peakRenderScale, pInlierPoints, siftRenderScale);
            peakExtractor.filterPeaksByInliers(qCanvasPeakList, peakRenderScale, qInlierPoints, siftRenderScale);

            // hack-y: since consensus sets are not supported for GD,
            //         returned list should contain one and only one CanvasMatches instance
            combinedCanvasMatches = siftMatchResult.getInlierMatchesList(p.getGroupId(),
                                                                         p.getId(),
                                                                         q.getGroupId(),
                                                                         q.getId(),
                                                                         siftRenderScale,
                                                                         pClipOffsets,
                                                                         qClipOffsets).get(0);
        }

        final CanvasPeakMatcher peakMatcher = new CanvasPeakMatcher(gdParameters, gdam.matchDerivationParameters);
        final CanvasMatchResult gdMatchResult = peakMatcher.deriveMatchResult(pCanvasPeakList, qCanvasPeakList);

        final List<PointMatch> combinedSiftScaleInliers = new ArrayList<>(siftScaledInliers);
        for (final PointMatch gdScalePointMatch : gdMatchResult.getInlierPointMatchList()) {
            combinedSiftScaleInliers.add(
                    new PointMatch(reScalePoint(peakRenderScale, gdScalePointMatch.getP1(), siftRenderScale),
                                   reScalePoint(peakRenderScale, gdScalePointMatch.getP2(), siftRenderScale)));
        }

        final List<CanvasMatches> gdResults = gdMatchResult.getInlierMatchesList(p.getGroupId(),
                                                                                 p.getId(),
                                                                                 q.getGroupId(),
                                                                                 q.getId(),
                                                                                 peakRenderScale,
                                                                                 pClipOffsets,
                                                                                 qClipOffsets);

        for (final CanvasMatches gdCanvasMatches : gdResults) {

            final Matches m = gdCanvasMatches.getMatches();

            if (gdParameters.gdStoredMatchWeight != null) {
                final double[] w = m.getWs();
                if (w != null) {
                    Arrays.fill(w, gdParameters.gdStoredMatchWeight);
                }
            }

            if (combinedCanvasMatches == null) {
                combinedCanvasMatches = gdCanvasMatches;
            } else {
                combinedCanvasMatches.append(gdCanvasMatches.getMatches());
            }
        }

        if (gdam.hasSufficiencyConstraints()) {

            if (combinedSiftScaleInliers.size() > siftScaledInliers.size()) {

                // if we found GD matches, assess quality of combined SIFT and GD match set

                // first, use SIFT filter to remove any inconsistent matches from the combined set
                final MatchFilter siftMatchFilter = siftMatchResult.getMatchFilter();
                final List<PointMatch> consistentCombinedSiftScaleInliers =
                        siftMatchFilter.filterMatches(combinedSiftScaleInliers, siftMatchFilter.getModel());

                final int badCombinedInlierCount = combinedSiftScaleInliers.size() -
                                                   consistentCombinedSiftScaleInliers.size();
                if (badCombinedInlierCount > 0) {
                    LOG.info("findGeometricDescriptorMatches: post-filter removed {} inconsistent combined inliers",
                             badCombinedInlierCount);
                }

                final PointMatchQualityStats combinedQualityStats = new PointMatchQualityStats();

                try {
                    combinedQualityStats.calculate(siftRenderScale,
                                                   pCanvasFeatures.getImageProcessorWidth(),
                                                   pCanvasFeatures.getImageProcessorHeight(),
                                                   pCanvasFeatures.getMaskProcessor(),
                                                   qCanvasFeatures.getImageProcessorWidth(),
                                                   qCanvasFeatures.getImageProcessorHeight(),
                                                   qCanvasFeatures.getMaskProcessor(),
                                                   Collections.singletonList(consistentCombinedSiftScaleInliers),
                                                   siftMatchResult.getAggregateModelForQualityChecks(),
                                                   siftFullScaleOverlapBlockRadius);
                } catch (final Exception e) {
                    throw new IllegalArgumentException("failed to fit aggregate model for point match quality calculation", e);
                }


                if (combinedQualityStats.hasSufficientQuantity(gdam.minCombinedInliers)) {

                    if (combinedQualityStats.hasSufficientCoverage(gdam.minCombinedCoveragePercentage)) {

                        // if inconsistent inliers were found, replace the original combined matches
                        // with full scale versions of the consistent subset of matches
                        if (badCombinedInlierCount > 0) {
                            final Matches consistentCombinedFullScaleMatches =
                                    CanvasMatchResult.convertPointMatchListToMatches(consistentCombinedSiftScaleInliers,
                                                                                     siftRenderScale,
                                                                                     pClipOffsets,
                                                                                     qClipOffsets);
                            //noinspection ConstantConditions
                            combinedCanvasMatches.setMatches(consistentCombinedFullScaleMatches);
                        }

                        LOG.info("findGeometricDescriptorMatches: saving {} combined matches",
                                 consistentCombinedSiftScaleInliers.size());
                        pairCounts.combinedSaved++;
                        pairMatchListForStage.add(combinedCanvasMatches);

                    } else {
                        LOG.info("findGeometricDescriptorMatches: dropping all matches because combined coverage is insufficient");
                        pairCounts.combinedPoorCoverage++;
                    }


                } else {
                    LOG.info("findGeometricDescriptorMatches: dropping all matches because only {} combined matches were found",
                             combinedSiftScaleInliers.size());
                    pairCounts.combinedPoorQuantity++;
                }


            } else {
                LOG.info("findGeometricDescriptorMatches: dropping SIFT matches because no GD matches were found");
                pairCounts.combinedPoorQuantity++;
            }

        } else if (combinedCanvasMatches == null) {
            LOG.info("findGeometricDescriptorMatches: no SIFT or GD matches were found, nothing to do");
            pairCounts.combinedPoorQuantity++;
        } else {
            LOG.info("findGeometricDescriptorMatches: saving {} combined matches", combinedCanvasMatches.size());
            pairCounts.combinedSaved++;
            pairMatchListForStage.add(combinedCanvasMatches);
        }

    }

    public List<StageResources> buildStageResourcesList(final RenderableCanvasIdPairs renderableCanvasIdPairs,
                                                        final String baseDataUrl,
                                                        final List<MatchStageParameters> stageParametersList) {

        LOG.info("buildStageResourcesList: entry, setting up resources for {} stages", stageParametersList.size());

        final String urlTemplateString = renderableCanvasIdPairs.getRenderParametersUrlTemplate(baseDataUrl);

        StageResources stageResources = null;
        final List<StageResources> stageResourcesList = new ArrayList<>();
        for (final MatchStageParameters stageParameters : stageParametersList) {
            stageResources = new StageResources(stageParameters,
                                                urlTemplateString,
                                                parameters.featureStorage,
                                                sourceImageProcessorCache,
                                                stageResources);
            stageResourcesList.add(stageResources);
        }

        final Map<CanvasFeatureExtractor, List<Integer>> featureExtractorToIndexMap = new HashMap<>();
        final Map<CanvasPeakExtractor, List<Integer>> peakExtractorToIndexMap = new HashMap<>();
        for (int i = 0; i < stageResourcesList.size(); i++) {
            stageResources = stageResourcesList.get(i);
            final List<Integer> indexesForFeatureExtractor =
                    featureExtractorToIndexMap.computeIfAbsent(stageResources.featureExtractor,
                                                               fe -> new ArrayList<>());
            indexesForFeatureExtractor.add(i);
            final List<Integer> indexesForPeakExtractor =
                    peakExtractorToIndexMap.computeIfAbsent(stageResources.peakExtractor,
                                                            pe -> new ArrayList<>());
            indexesForPeakExtractor.add(i);
        }

        featureExtractorToIndexMap.values()
                .forEach(indexList -> indexList
                        .forEach(i -> stageResourcesList.get(i).featureLoaderName = getLoaderName("feature",
                                                                                                  indexList)));

        peakExtractorToIndexMap.values()
                .forEach(indexList -> indexList
                        .forEach(i -> stageResourcesList.get(i).peakLoaderName = getLoaderName("peak",
                                                                                               indexList)));

        LOG.info("buildStageResourcesList: feature loader names are {}",
                 stageResourcesList.stream().map(sr -> sr.featureLoaderName).collect(Collectors.toList()));

        LOG.info("buildStageResourcesList: peak loader names are {}",
                 stageResourcesList.stream().map(sr -> sr.peakLoaderName).collect(Collectors.toList()));

        return stageResourcesList;
    }

    private String getLoaderName(final String context,
                                 final List<Integer> indexList) {
        final StringBuilder sb = new StringBuilder(context);
        for (final Integer i : indexList) {
            sb.append("_").append(i);
        }
        return sb.toString();
    }

    private List<CanvasMatches> storeMatches(final List<CanvasMatches> allMatchesList)
            throws IOException {

        final List<CanvasMatches> nonEmptyMatchesList =
                allMatchesList.stream().filter(m -> m.size() > 0).collect(Collectors.toList());

        matchStorageClient.saveMatches(nonEmptyMatchesList);

        return nonEmptyMatchesList;
    }

    private void logStats() {
        stageNameToPairCountsMap.keySet().stream()
                .sorted()
                .forEach(stageName -> stageNameToPairCountsMap.get(stageName).logStats(stageName));
    }

    private static final Logger LOG = LoggerFactory.getLogger(MultiStagePointMatchClient.class);

    private static Point reScalePoint(final double fromScale,
                                      final Point point,
                                      final double toScale) {
        final double[] reScaledLocal = {
                (point.getL()[0] / fromScale) * toScale,
                (point.getL()[1] / fromScale) * toScale
        };
        return new Point(reScaledLocal);
    }

    public static class StageResources {

        public final MatchStageParameters stageParameters;
        public final CanvasRenderParametersUrlTemplate siftUrlTemplateForStage;
        public final boolean siftUrlTemplateMatchesPriorStageTemplate;
        public final CanvasFeatureExtractor featureExtractor;
        public final CanvasFeatureListLoader featureLoader;
        public final CanvasFeatureMatcher featureMatcher;
        public final CanvasRenderParametersUrlTemplate gdUrlTemplateForStage;
        public final boolean gdUrlTemplateMatchesPriorStageTemplate;
        public final CanvasPeakExtractor peakExtractor;
        public final CanvasPeakListLoader peakLoader;

        public String featureLoaderName;
        public String peakLoaderName;

        public StageResources(final MatchStageParameters stageParameters,
                              final String urlTemplateString,
                              final FeatureStorageParameters featureStorageParameters,
                              final ImageProcessorCache sourceImageProcessorCache,
                              @Nullable final StageResources priorStageResources) {

            this.stageParameters = stageParameters;

            // determine unique set of extractors (e.g. stage_1, stage_2_3, stage_4, ...)
            // map render context to name (e.g. stage_1, stage_2, ...)
            // add render context name to key object (CanvasIdWithRenderContext)
            // loader maps context name to extractor instances

            final FeatureRenderParameters featureRenderParameters =
                    stageParameters.getFeatureRender();
            final FeatureRenderClipParameters featureRenderClipParameters =
                    stageParameters.getFeatureRenderClip();
            final FeatureExtractionParameters featureExtractionParameters =
                    stageParameters.getFeatureExtraction();
            final MatchDerivationParameters matchDerivationParameters =
                    stageParameters.getFeatureMatchDerivation();
            final GeometricDescriptorAndMatchFilterParameters gdam =
                    stageParameters.getGeometricDescriptorAndMatch();

            this.siftUrlTemplateForStage =
                    CanvasRenderParametersUrlTemplate.getTemplateForRun(urlTemplateString,
                                                                        featureRenderParameters,
                                                                        featureRenderClipParameters);
            this.siftUrlTemplateMatchesPriorStageTemplate =
                    (priorStageResources != null) &&
                    this.siftUrlTemplateForStage.matchesExceptForScale(priorStageResources.siftUrlTemplateForStage);

            this.featureExtractor = SIFTPointMatchClient.getCanvasFeatureExtractor(featureExtractionParameters);
            this.featureLoader =
                    new CanvasFeatureListLoader(
                            this.featureExtractor,
                            featureStorageParameters.getRootFeatureDirectory(),
                            featureStorageParameters.requireStoredFeatures);

            this.featureLoader.enableSourceDataCaching(sourceImageProcessorCache);

            this.featureMatcher = new CanvasFeatureMatcher(matchDerivationParameters);

            if (gdam.isGeometricDescriptorMatchingEnabled()) {

                if (CONSENSUS_SETS.equals(matchDerivationParameters.matchFilter)) {
                    throw new IllegalArgumentException(
                            "Geometric Descriptor matching is not supported when SIFT matches are grouped into CONSENSUS_SETS");
                }

                this.gdUrlTemplateForStage =
                        CanvasRenderParametersUrlTemplate.getTemplateForRun(
                                urlTemplateString,
                                featureRenderParameters.copy(gdam.renderScale,
                                                             gdam.renderWithFilter,
                                                             gdam.renderFilterListName),
                                featureRenderClipParameters);

                this.gdUrlTemplateMatchesPriorStageTemplate =
                        (priorStageResources != null) &&
                        (priorStageResources.gdUrlTemplateForStage != null) &&
                        this.gdUrlTemplateForStage.matchesExceptForScale(priorStageResources.gdUrlTemplateForStage);

                this.peakExtractor = new CanvasPeakExtractor(gdam.geometricDescriptorParameters);
                this.peakExtractor.setImageProcessorCache(sourceImageProcessorCache);

                this.peakLoader = new CanvasPeakListLoader(this.peakExtractor);

            } else {

                this.gdUrlTemplateForStage = null;
                this.gdUrlTemplateMatchesPriorStageTemplate = false;
                this.peakExtractor = null;
                this.peakLoader = null;

            }

        }

        public CanvasIdWithRenderContext getFeatureContextCanvasId(final CanvasId canvasId,
                                                                   final CanvasIdWithRenderContext priorStageCanvasId) {
            final CanvasIdWithRenderContext matchingPriorStageCanvasId =
                    siftUrlTemplateMatchesPriorStageTemplate ? priorStageCanvasId : null;
            return CanvasIdWithRenderContext.build(canvasId,
                                                   siftUrlTemplateForStage,
                                                   featureLoaderName,
                                                   matchingPriorStageCanvasId);
        }

        public CanvasIdWithRenderContext getPeakContextCanvasId(final CanvasId canvasId,
                                                                final CanvasIdWithRenderContext priorStageCanvasId) {
            final CanvasIdWithRenderContext matchingPriorStageCanvasId =
                    gdUrlTemplateMatchesPriorStageTemplate ? priorStageCanvasId : null;
            return CanvasIdWithRenderContext.build(canvasId,
                                                   gdUrlTemplateForStage,
                                                   peakLoaderName,
                                                   matchingPriorStageCanvasId);
        }
    }

    private static class MatchPairCounts {
        private long siftPoorCoverage = 0;
        private long siftPoorQuantity = 0;
        private long siftSaved = 0;
        private long combinedPoorCoverage = 0;
        private long combinedPoorQuantity = 0;
        private long combinedSaved = 0;

        public long getTotalSaved() {
            return siftSaved + combinedSaved;
        }

        public long getTotalProcessed() {
            return siftPoorCoverage + siftPoorQuantity + siftSaved +
                   combinedPoorCoverage + combinedPoorQuantity + combinedSaved;
        }

        public void logStats(final String stageName) {
            final int percentSaved = (int) ((getTotalSaved() / (double) getTotalProcessed()) * 100);
            LOG.info("logStats: for stage {}, saved matches for {} out of {} pairs ({}%), siftPoorCoverage: {}, siftPoorQuantity: {}, siftSaved: {}, combinedPoorCoverage: {}, combinedPoorQuantity: {}, combinedSaved: {}, ",
                     stageName,
                     getTotalSaved(),
                     getTotalProcessed(),
                     percentSaved,
                     siftPoorCoverage,
                     siftPoorQuantity,
                     siftSaved,
                     combinedPoorCoverage,
                     combinedPoorQuantity,
                     combinedSaved);
        }

    }

}

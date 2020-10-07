package org.janelia.alignment.match.stage;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import mpicbg.imglib.algorithm.scalespace.DifferenceOfGaussianPeak;
import mpicbg.imglib.type.numeric.real.FloatType;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;

import org.janelia.alignment.match.CanvasIdWithRenderContext;
import org.janelia.alignment.match.CanvasMatchResult;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.CanvasPeakExtractor;
import org.janelia.alignment.match.CanvasPeakMatcher;
import org.janelia.alignment.match.MatchFilter;
import org.janelia.alignment.match.Matches;
import org.janelia.alignment.match.PointMatchQualityStats;
import org.janelia.alignment.match.cache.CachedCanvasFeatures;
import org.janelia.alignment.match.cache.CachedCanvasPeaks;
import org.janelia.alignment.match.cache.CanvasFeatureProvider;
import org.janelia.alignment.match.cache.CanvasPeakProvider;
import org.janelia.alignment.match.parameters.FeatureRenderParameters;
import org.janelia.alignment.match.parameters.GeometricDescriptorAndMatchFilterParameters;
import org.janelia.alignment.match.parameters.GeometricDescriptorParameters;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Supports canvas pair match generation for one stage in a multi-stage matching process.
 *
 * @author Eric Trautman
 */
public class StageMatcher {

    /**
     * Encapsulates canvas pair match generation results for one stage in a multi-stage matching process.
     */
    public static class PairResult
            implements Serializable {

        @SuppressWarnings({"FieldCanBeLocal", "unused"})
        private final StageMatcher stageMatcher;
        private final List<CanvasMatches> canvasMatchesList;
        private StageMatchingStats siftStats;
        private StageMatchingStats gdStats;

        public PairResult(final StageMatcher stageMatcher) {
            this.stageMatcher = stageMatcher;
            this.canvasMatchesList = new ArrayList<>();
            this.siftStats = null;
            this.gdStats = null;
        }

        public List<CanvasMatches> getCanvasMatchesList() {
            return canvasMatchesList;
        }

        public int size() {
            return canvasMatchesList.size();
        }

        public void addCanvasMatches(final CanvasMatches canvasMatches) {
            canvasMatchesList.add(canvasMatches);
        }

        public StageMatchingStats getSiftStats() {
            return siftStats;
        }

        public void setSiftStats(final StageMatchingStats siftStats) {
            this.siftStats = siftStats;
        }

        public StageMatchingStats getGdStats() {
            return gdStats;
        }

        public void setGdStats(final StageMatchingStats gdStats) {
            this.gdStats = gdStats;
        }
    }

    private final StageMatchingResources stageResources;
    private final CanvasFeatureProvider featureProvider;
    private final CanvasPeakProvider peakProvider;
    private final boolean saveStats;
    private final StageMatchPairCounts pairCounts;

    public StageMatcher(final StageMatchingResources stageResources,
                        final boolean saveStats) {
        this(stageResources, stageResources, stageResources, saveStats);
    }

    public StageMatcher(final StageMatchingResources stageResources,
                        final CanvasFeatureProvider featureProvider,
                        final CanvasPeakProvider peakProvider,
                        final boolean saveStats) {
        this.stageResources = stageResources;
        this.featureProvider = featureProvider;
        this.peakProvider = peakProvider;
        this.saveStats = saveStats;
        this.pairCounts = new StageMatchPairCounts();
    }

    public void logPairCountStats() {
        pairCounts.logStats(stageResources.getStageName());
    }

    public StageMatchingResources getStageResources() {
        return stageResources;
    }

    public PairResult generateStageMatchesForPair(final CanvasIdWithRenderContext pFeatureContextCanvasId,
                                                  final CanvasIdWithRenderContext qFeatureContextCanvasId,
                                                  final CanvasIdWithRenderContext pPeakContextCanvasId,
                                                  final CanvasIdWithRenderContext qPeakContextCanvasId) {

        final PairResult stagePairResult = new PairResult(this);

        final String stageName = stageResources.getStageName();

        LOG.info("generateStageMatchesForPair: derive matches between {} and {} for stage {}",
                 pFeatureContextCanvasId.getCanvasId(), qFeatureContextCanvasId.getCanvasId(), stageName);

        final long pFeatureStart = System.currentTimeMillis();
        final CachedCanvasFeatures pFeatures = featureProvider.getCanvasFeatures(pFeatureContextCanvasId);

        final long qFeatureStart = System.currentTimeMillis();
        final CachedCanvasFeatures qFeatures = featureProvider.getCanvasFeatures(qFeatureContextCanvasId);

        final long featureMatchStart = System.currentTimeMillis();
        final CanvasMatchResult matchResult =
                stageResources.getFeatureMatcher().deriveMatchResult(pFeatures.getFeatureList(),
                                                                     qFeatures.getFeatureList());
        final long featureMatchStop = System.currentTimeMillis();

        final double[] pClipOffsets = pFeatures.getClipOffsets();
        final double[] qClipOffsets = qFeatures.getClipOffsets();

        final MatchDerivationParameters featureMatchDerivation = stageResources.getFeatureMatchDerivation();

        long siftQualityStop = featureMatchStop;
        PointMatchQualityStats siftQualityStats = null;

        if (stageResources.hasGeometricDescriptorData()) {

            final double siftFullScaleOverlapBlockRadius =
                    stageResources.getFeatureMatchDerivation().matchFullScaleCoverageRadius;

            siftQualityStats = matchResult.calculateQualityStats(pFeatures.getRenderParameters(),
                                                                 pFeatures.getMaskProcessor(),
                                                                 qFeatures.getRenderParameters(),
                                                                 qFeatures.getMaskProcessor(),
                                                                 siftFullScaleOverlapBlockRadius);
            siftQualityStop = System.currentTimeMillis();

            appendGeometricMatchesIfNecessary(stageResources,
                                              pFeatures,
                                              qFeatures,
                                              peakProvider,
                                              stagePairResult,
                                              pPeakContextCanvasId,
                                              qPeakContextCanvasId,
                                              matchResult,
                                              siftQualityStats,
                                              pairCounts);
        } else {

            if (matchResult.getTotalNumberOfInliers() > 0) {

                final FeatureRenderParameters featureRender = stageResources.getFeatureRender();
                final Double siftMinCoveragePercentage = featureMatchDerivation.matchMinCoveragePercentage;
                boolean keepMatches = true;

                if (siftMinCoveragePercentage != null) {
                    siftQualityStats =
                            matchResult.calculateQualityStats(pFeatures.getRenderParameters(),
                                                              pFeatures.getMaskProcessor(),
                                                              qFeatures.getRenderParameters(),
                                                              qFeatures.getMaskProcessor(),
                                                              featureMatchDerivation.matchFullScaleCoverageRadius);
                    keepMatches = siftQualityStats.hasSufficientCoverage(siftMinCoveragePercentage);
                } else if (saveStats) {
                    siftQualityStats =
                            matchResult.calculateQualityStats(pFeatures.getRenderParameters(),
                                                              pFeatures.getMaskProcessor(),
                                                              qFeatures.getRenderParameters(),
                                                              qFeatures.getMaskProcessor(),
                                                              featureMatchDerivation.matchFullScaleCoverageRadius);
                }

                siftQualityStop = System.currentTimeMillis();

                if (keepMatches) {
                    // since no GD parameters were specified, simply save SIFT results
                    matchResult.addInlierMatchesToList(pFeatureContextCanvasId.getGroupId(),
                                                       pFeatureContextCanvasId.getId(),
                                                       qFeatureContextCanvasId.getGroupId(),
                                                       qFeatureContextCanvasId.getId(),
                                                       featureRender.renderScale,
                                                       pClipOffsets,
                                                       qClipOffsets,
                                                       stagePairResult.getCanvasMatchesList());
                    pairCounts.incrementSiftSaved();
                } else {
                    LOG.info("generateStageMatchesForPair: dropping SIFT matches because coverage is insufficient");
                    pairCounts.incrementSiftPoorCoverage();
                }

            } else {
                LOG.info("generateStageMatchesForPair: no SIFT matches to save");
                pairCounts.incrementSiftPoorQuantity();
            }

        }

        if (saveStats) {
            stagePairResult.setSiftStats(
                    new StageMatchingStats(pFeatures.size(),
                                           qFeatureStart - pFeatureStart,
                                           qFeatures.size(),
                                           featureMatchStart - qFeatureStart,
                                           matchResult.getConsensusSetSizes(),
                                           featureMatchStop - featureMatchStart,
                                           siftQualityStats,
                                           siftQualityStop - featureMatchStop));
        }

        LOG.info("generateStageMatchesForPair: exit, returning list with {} element(s) for pair {} and {} in stage {}",
                 stagePairResult.size(),
                 pFeatureContextCanvasId.getCanvasId(),
                 qFeatureContextCanvasId.getCanvasId(),
                 stageName);

        return stagePairResult;
    }

    private void appendGeometricMatchesIfNecessary(final StageMatchingResources stageResources,
                                                   final CachedCanvasFeatures pCanvasFeatures,
                                                   final CachedCanvasFeatures qCanvasFeatures,
                                                   final CanvasPeakProvider peakProvider,
                                                   final PairResult stagePairResult,
                                                   final CanvasIdWithRenderContext pPeakContextCanvasId,
                                                   final CanvasIdWithRenderContext qPeakContextCanvasId,
                                                   final CanvasMatchResult siftMatchResult,
                                                   final PointMatchQualityStats siftQualityStats,
                                                   final StageMatchPairCounts pairCounts) {

        final GeometricDescriptorAndMatchFilterParameters gdam = stageResources.getGeometricDescriptorAndMatch();

        final double[] pClipOffsets = pCanvasFeatures.getClipOffsets();
        final double[] qClipOffsets = qCanvasFeatures.getClipOffsets();

        if (gdam.hasSufficiencyConstraints()) {

            // check SIFT results to see if GD is needed ...

            if (siftQualityStats.hasSufficientQuantity(gdam.minCombinedInliers)) {

                if (siftQualityStats.hasSufficientCoverage(gdam.minCombinedCoveragePercentage)) {

                    LOG.info("appendGeometricMatchesIfNecessary: saving {} SIFT matches and skipping Geometric process",
                             siftMatchResult.getTotalNumberOfInliers());
                    pairCounts.incrementSiftSaved();

                    siftMatchResult.addInlierMatchesToList(pPeakContextCanvasId.getGroupId(),
                                                           pPeakContextCanvasId.getId(),
                                                           qPeakContextCanvasId.getGroupId(),
                                                           qPeakContextCanvasId.getId(),
                                                           pCanvasFeatures.getRenderParameters().getScale(),
                                                           pClipOffsets,
                                                           qClipOffsets,
                                                           stagePairResult.getCanvasMatchesList());

                } else {

                    LOG.info("appendGeometricMatchesIfNecessary: running Geometric process to improve coverage");

                    findGeometricDescriptorMatches(stageResources,
                                                   peakProvider,
                                                   stagePairResult,
                                                   pPeakContextCanvasId,
                                                   pCanvasFeatures,
                                                   qPeakContextCanvasId,
                                                   qCanvasFeatures,
                                                   siftMatchResult,
                                                   pairCounts);
                }

            } else if (gdam.runGeoRegardlessOfSiftResults) {

                LOG.info("appendGeometricMatchesIfNecessary: running Geometric process to improve quantity");

                findGeometricDescriptorMatches(stageResources,
                                               peakProvider,
                                               stagePairResult,
                                               pPeakContextCanvasId,
                                               pCanvasFeatures,
                                               qPeakContextCanvasId,
                                               qCanvasFeatures,
                                               siftMatchResult,
                                               pairCounts);

            } else {
                LOG.info("appendGeometricMatchesIfNecessary: dropping SIFT matches and skipping Geometric process because only {} matches were found",
                         siftMatchResult.getTotalNumberOfInliers());
                pairCounts.incrementSiftPoorQuantity();
            }


        } else {

            LOG.info("appendGeometricMatchesIfNecessary: running Geometric process since sufficiency constraints are not defined");

            findGeometricDescriptorMatches(stageResources,
                                           peakProvider,
                                           stagePairResult,
                                           pPeakContextCanvasId,
                                           pCanvasFeatures,
                                           qPeakContextCanvasId,
                                           qCanvasFeatures,
                                           siftMatchResult,
                                           pairCounts);

        }
    }

    private void findGeometricDescriptorMatches(final StageMatchingResources stageResources,
                                                final CanvasPeakProvider peakProvider,
                                                final PairResult stagePairResult,
                                                final CanvasIdWithRenderContext pPeakContextCanvasId,
                                                final CachedCanvasFeatures pCanvasFeatures,
                                                final CanvasIdWithRenderContext qPeakContextCanvasId,
                                                final CachedCanvasFeatures qCanvasFeatures,
                                                final CanvasMatchResult siftMatchResult,
                                                final StageMatchPairCounts pairCounts) {

        final GeometricDescriptorAndMatchFilterParameters gdam =
                stageResources.getGeometricDescriptorAndMatch();
        final double siftFullScaleOverlapBlockRadius =
                stageResources.getFeatureMatchDerivation().matchFullScaleCoverageRadius;
        final CanvasPeakExtractor peakExtractor = stageResources.getPeakExtractor();

        final double siftRenderScale = pCanvasFeatures.getRenderParameters().getScale();
        final double[] pClipOffsets = pCanvasFeatures.getClipOffsets();
        final double[] qClipOffsets = qCanvasFeatures.getClipOffsets();

        final GeometricDescriptorParameters gdParameters = gdam.geometricDescriptorParameters;
        final double peakRenderScale = gdam.renderScale;

        final long pPeakStart = System.currentTimeMillis();
        final CachedCanvasPeaks pCanvasPeaks = peakProvider.getCanvasPeaks(pPeakContextCanvasId);

        final long qPeakStart = System.currentTimeMillis();
        final CachedCanvasPeaks qCanvasPeaks = peakProvider.getCanvasPeaks(qPeakContextCanvasId);

        final long peakMatchStart = System.currentTimeMillis();

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
            combinedCanvasMatches = siftMatchResult.getInlierMatchesList(pPeakContextCanvasId.getGroupId(),
                                                                         pPeakContextCanvasId.getId(),
                                                                         qPeakContextCanvasId.getGroupId(),
                                                                         qPeakContextCanvasId.getId(),
                                                                         siftRenderScale,
                                                                         pClipOffsets,
                                                                         qClipOffsets).get(0);
        }


        final CanvasPeakMatcher peakMatcher = new CanvasPeakMatcher(gdParameters,
                                                                    gdam.matchDerivationParameters,
                                                                    gdam.renderScale);
        final CanvasMatchResult gdMatchResult = peakMatcher.deriveMatchResult(pCanvasPeakList, qCanvasPeakList);

        final List<PointMatch> combinedSiftScaleInliers = new ArrayList<>(siftScaledInliers);
        for (final PointMatch gdScalePointMatch : gdMatchResult.getInlierPointMatchList()) {
            combinedSiftScaleInliers.add(
                    new PointMatch(reScalePoint(peakRenderScale, gdScalePointMatch.getP1(), siftRenderScale),
                                   reScalePoint(peakRenderScale, gdScalePointMatch.getP2(), siftRenderScale)));
        }

        final List<CanvasMatches> gdResults = gdMatchResult.getInlierMatchesList(pPeakContextCanvasId.getGroupId(),
                                                                                 pPeakContextCanvasId.getId(),
                                                                                 qPeakContextCanvasId.getGroupId(),
                                                                                 qPeakContextCanvasId.getId(),
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

        final long peakMatchStop = System.currentTimeMillis();

        final MatchFilter siftMatchFilter = siftMatchResult.getMatchFilter();
        PointMatchQualityStats combinedQualityStats = null;

        if (gdam.hasSufficiencyConstraints()) {

            if (combinedSiftScaleInliers.size() > siftScaledInliers.size()) {

                // use SIFT filter to remove any inconsistent matches from the combined set
                final List<PointMatch> consistentCombinedSiftScaleInliers =
                        siftMatchFilter.filterMatches(combinedSiftScaleInliers, siftMatchFilter.getModel());

                final int badCombinedInlierCount =
                        combinedSiftScaleInliers.size() - consistentCombinedSiftScaleInliers.size();

                if (badCombinedInlierCount > 0) {
                    LOG.info("findGeometricDescriptorMatches: post-filter removed {} inconsistent combined inliers",
                             badCombinedInlierCount);
                }

                // if we found GD matches, assess quality of combined SIFT and GD match set
                combinedQualityStats = deriveCombinedQualityStats(pCanvasFeatures,
                                                                  qCanvasFeatures,
                                                                  siftMatchResult,
                                                                  siftFullScaleOverlapBlockRadius,
                                                                  siftRenderScale,
                                                                  consistentCombinedSiftScaleInliers);

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
                        pairCounts.incrementCombinedSaved();
                        stagePairResult.addCanvasMatches(combinedCanvasMatches);

                    } else {
                        LOG.info("findGeometricDescriptorMatches: dropping all matches because combined coverage is insufficient");
                        pairCounts.incrementCombinedPoorCoverage();
                    }


                } else {
                    LOG.info("findGeometricDescriptorMatches: dropping all matches because only {} combined matches were found",
                             combinedSiftScaleInliers.size());
                    pairCounts.incrementCombinedPoorQuantity();
                }


            } else {
                LOG.info("findGeometricDescriptorMatches: dropping SIFT matches because no GD matches were found");
                pairCounts.incrementCombinedPoorQuantity();
            }

        } else if (combinedCanvasMatches == null) {
            LOG.info("findGeometricDescriptorMatches: no SIFT or GD matches were found, nothing to do");
            pairCounts.incrementCombinedPoorQuantity();
        } else {
            LOG.info("findGeometricDescriptorMatches: saving {} combined matches", combinedCanvasMatches.size());
            pairCounts.incrementCombinedSaved();
            stagePairResult.addCanvasMatches(combinedCanvasMatches);
        }

        if (saveStats) {

            if (combinedQualityStats == null) {

                // derive combined stats for analysis if they were not already generated

                // use SIFT filter to remove any inconsistent matches from the combined set
                final List<PointMatch> consistentCombinedSiftScaleInliers =
                        siftMatchFilter.filterMatches(combinedSiftScaleInliers, siftMatchFilter.getModel());

                combinedQualityStats = deriveCombinedQualityStats(pCanvasFeatures,
                                                                  qCanvasFeatures,
                                                                  siftMatchResult,
                                                                  siftFullScaleOverlapBlockRadius,
                                                                  siftRenderScale,
                                                                  consistentCombinedSiftScaleInliers);
            }

            stagePairResult.setGdStats(
                    new StageMatchingStats(pCanvasPeakList.size(),
                                           qPeakStart - pPeakStart,
                                           qCanvasPeakList.size(),
                                           peakMatchStart - qPeakStart,
                                           gdMatchResult.getConsensusSetSizes(),
                                           peakMatchStop - peakMatchStart,
                                           combinedQualityStats,
                                           System.currentTimeMillis() - peakMatchStop));
        }
    }

    private PointMatchQualityStats deriveCombinedQualityStats(final CachedCanvasFeatures pCanvasFeatures,
                                                              final CachedCanvasFeatures qCanvasFeatures,
                                                              final CanvasMatchResult siftMatchResult,
                                                              final double siftFullScaleOverlapBlockRadius,
                                                              final double siftRenderScale,
                                                              final List<PointMatch> consistentCombinedSiftScaleInliers) {

        final PointMatchQualityStats combinedQualityStats = new PointMatchQualityStats();

        try {
            combinedQualityStats.calculate(siftRenderScale,
                                           toFullScaleSize(pCanvasFeatures.getImageProcessorWidth(),
                                                           siftRenderScale),
                                           toFullScaleSize(pCanvasFeatures.getImageProcessorHeight(),
                                                           siftRenderScale),
                                           pCanvasFeatures.getMaskProcessor(),
                                           toFullScaleSize(qCanvasFeatures.getImageProcessorWidth(),
                                                           siftRenderScale),
                                           toFullScaleSize(qCanvasFeatures.getImageProcessorHeight(),
                                                           siftRenderScale),
                                           qCanvasFeatures.getMaskProcessor(),
                                           Collections.singletonList(consistentCombinedSiftScaleInliers),
                                           siftMatchResult.getAggregateModelForQualityChecks(),
                                           siftFullScaleOverlapBlockRadius);
        } catch (final Exception e) {
            throw new IllegalArgumentException("failed to fit aggregate model for point match quality calculation", e);
        }

        return combinedQualityStats;
    }

    private static Point reScalePoint(final double fromScale,
                                      final Point point,
                                      final double toScale) {
        final double[] reScaledLocal = {
                (point.getL()[0] / fromScale) * toScale,
                (point.getL()[1] / fromScale) * toScale
        };
        return new Point(reScaledLocal);
    }

    public static long toFullScaleSize(final int scaledSize,
                                       final double renderScale) {
        return (long) Math.ceil(scaledSize / renderScale);
    }

    private static final Logger LOG = LoggerFactory.getLogger(StageMatcher.class);
}

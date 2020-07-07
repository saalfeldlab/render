package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import mpicbg.imagefeatures.FloatArray2DSIFT;
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
import org.janelia.alignment.util.FileUtil;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.cache.CachedCanvasFeatures;
import org.janelia.render.client.cache.CachedCanvasPeaks;
import org.janelia.render.client.cache.CanvasDataCache;
import org.janelia.render.client.cache.CanvasFeatureListLoader;
import org.janelia.render.client.cache.CanvasPeakListLoader;
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
public class SIFTPointMatchClient
        implements Serializable {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        MatchWebServiceParameters matchClient = new MatchWebServiceParameters();

        @ParametersDelegate
        FeatureRenderParameters featureRender = new FeatureRenderParameters();

        @ParametersDelegate
        FeatureRenderClipParameters featureRenderClip = new FeatureRenderClipParameters();

        @ParametersDelegate
        FeatureExtractionParameters featureExtraction = new FeatureExtractionParameters();

        @ParametersDelegate
        FeatureStorageParameters featureStorage = new FeatureStorageParameters();

        @ParametersDelegate
        MatchDerivationParameters matchDerivation = new MatchDerivationParameters();

        @ParametersDelegate
        GeometricDescriptorAndMatchFilterParameters geometricDescriptorAndMatch =
                new GeometricDescriptorAndMatchFilterParameters();

        @Parameter(
                names = { "--gdMaxPeakCacheGb" },
                description = "Maximum number of gigabytes of peaks to cache")
        public Integer maxPeakCacheGb = 2;

        @Parameter(
                names = "--failedPairsDir",
                description = "Write failed pairs (ones that did not have matches) to a JSON file in this directory",
                arity = 1)
        public String failedPairsDir;

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

                if (parameters.geometricDescriptorAndMatch.isGeometricDescriptorMatchingEnabled()) {
                    parameters.geometricDescriptorAndMatch.validateAndSetDefaults();
                }

                LOG.info("runClient: entry, parameters={}", parameters);

                final SIFTPointMatchClient client = new SIFTPointMatchClient(parameters);
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
    private final MatchPairCounts pairCounts;

    SIFTPointMatchClient(final Parameters parameters) throws IllegalArgumentException {
        this.parameters = parameters;
        this.matchStorageClient = new RenderDataClient(parameters.matchClient.baseDataUrl,
                                                       parameters.matchClient.owner,
                                                       parameters.matchClient.collection);
        this.pairCounts = new MatchPairCounts();

        // make sure the failed pairs directory exists before we get started
        if (parameters.failedPairsDir != null) {
            FileUtil.ensureWritableDirectory(new File(parameters.failedPairsDir));
        }
    }

    private void generateMatchesForPairFile(final String pairJsonFileName)
            throws IOException {

        LOG.info("generateMatchesForPairFile: pairJsonFileName is {}", pairJsonFileName);

        final RenderableCanvasIdPairs renderableCanvasIdPairs = RenderableCanvasIdPairs.load(pairJsonFileName);

        final List<CanvasMatches> nonEmptyMatchesList =
                generateMatchesForPairs(renderableCanvasIdPairs,
                                        parameters.matchClient.baseDataUrl,
                                        parameters.featureRender,
                                        parameters.featureRenderClip,
                                        parameters.featureExtraction,
                                        parameters.featureStorage,
                                        parameters.matchDerivation);

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

    List<CanvasMatches> generateMatchesForPairs(final RenderableCanvasIdPairs renderableCanvasIdPairs,
                                                final String baseDataUrl,
                                                final FeatureRenderParameters featureRenderParameters,
                                                final FeatureRenderClipParameters featureRenderClipParameters,
                                                final FeatureExtractionParameters featureExtractionParameters,
                                                final FeatureStorageParameters featureStorageParameters,
                                                final MatchDerivationParameters matchDerivationParameters)
            throws IOException {

        final CanvasRenderParametersUrlTemplate siftUrlTemplateForRun =
                CanvasRenderParametersUrlTemplate.getTemplateForRun(
                        renderableCanvasIdPairs.getRenderParametersUrlTemplate(baseDataUrl),
                        featureRenderParameters,
                        featureRenderClipParameters);

        final CanvasFeatureListLoader featureLoader =
                new CanvasFeatureListLoader(
                        getCanvasFeatureExtractor(featureExtractionParameters),
                        featureStorageParameters.getRootFeatureDirectory(),
                        featureStorageParameters.requireStoredFeatures);

        final long featureCacheMaxKilobytes = featureStorageParameters.maxFeatureCacheGb * 1_000_000;
        final CanvasDataCache featureDataCache = CanvasDataCache.getSharedCache(featureCacheMaxKilobytes, featureLoader);

        final long maximumNumberOfCachedSourcePixels =
                featureStorageParameters.maxFeatureSourceCacheGb * 1_000_000_000;
        final ImageProcessorCache sourceImageProcessorCache =
                new ImageProcessorCache(maximumNumberOfCachedSourcePixels,
                                        true,
                                        false);
        featureLoader.enableSourceDataCaching(sourceImageProcessorCache);

        final CanvasFeatureMatcher featureMatcher = new CanvasFeatureMatcher(matchDerivationParameters);

        final GeometricDescriptorAndMatchFilterParameters gdam = parameters.geometricDescriptorAndMatch;
        CanvasRenderParametersUrlTemplate gdUrlTemplateForRun = null;

        final CanvasDataCache peakDataCache;
        final CanvasPeakExtractor peakExtractor;
        if (gdam.isGeometricDescriptorMatchingEnabled()) {

            if (CONSENSUS_SETS.equals(matchDerivationParameters.matchFilter)) {
                throw new IllegalArgumentException(
                        "Geometric Descriptor matching is not supported when SIFT matches are grouped into CONSENSUS_SETS");
            }

            gdUrlTemplateForRun =
                    CanvasRenderParametersUrlTemplate.getTemplateForRun(
                            renderableCanvasIdPairs.getRenderParametersUrlTemplate(baseDataUrl),
                            featureRenderParameters.copy(gdam.renderScale,
                                                         gdam.renderWithFilter,
                                                         gdam.renderFilterListName),
                            featureRenderClipParameters);

            peakExtractor = new CanvasPeakExtractor(gdam.geometricDescriptorParameters);
            peakExtractor.setImageProcessorCache(sourceImageProcessorCache);

            final CanvasPeakListLoader peakLoader = new CanvasPeakListLoader(peakExtractor);

            final long peakCacheMaxKilobytes = parameters.maxPeakCacheGb * 1000000;
            peakDataCache = CanvasDataCache.getSharedCache(peakCacheMaxKilobytes, peakLoader);

        } else {
            peakDataCache = null;
            peakExtractor = null;
        }

        final List<CanvasMatches> matchList = new ArrayList<>();

        CanvasId p;
        CanvasId q;
        CachedCanvasFeatures pFeatures;
        CachedCanvasFeatures qFeatures;
        CanvasMatchResult matchResult;
        for (final OrderedCanvasIdPair pair : renderableCanvasIdPairs.getNeighborPairs()) {
            p = pair.getP();
            q = pair.getQ();

            LOG.info("generateMatchesForPairs: derive matches between {} and {}", p, q);

            pFeatures = featureDataCache.getCanvasFeatures(CanvasIdWithRenderContext.build(p, siftUrlTemplateForRun));
            qFeatures = featureDataCache.getCanvasFeatures(CanvasIdWithRenderContext.build(q, siftUrlTemplateForRun));

            matchResult = featureMatcher.deriveMatchResult(pFeatures.getFeatureList(),
                                                           qFeatures.getFeatureList());

            final double[] pClipOffsets = pFeatures.getClipOffsets();
            final double[] qClipOffsets = qFeatures.getClipOffsets();

            if (peakExtractor == null) {

                if (matchResult.getTotalNumberOfInliers() > 0) {

                    final Double siftMinCoveragePercentage = matchDerivationParameters.matchMinCoveragePercentage;
                    boolean keepMatches = true;

                    if (siftMinCoveragePercentage != null) {
                        final PointMatchQualityStats siftQualityStats =
                                matchResult.calculateQualityStats(pFeatures.getRenderParameters(),
                                                                  pFeatures.getMaskProcessor(),
                                                                  qFeatures.getRenderParameters(),
                                                                  qFeatures.getMaskProcessor(),
                                                                  matchDerivationParameters.matchFullScaleCoverageRadius);
                        keepMatches = siftQualityStats.hasSufficientCoverage(siftMinCoveragePercentage);
                    }

                    if (keepMatches) {
                        // since no GD parameters were specified, simply save SIFT results
                        matchResult.addInlierMatchesToList(p.getGroupId(),
                                                           p.getId(),
                                                           q.getGroupId(),
                                                           q.getId(),
                                                           featureRenderParameters.renderScale,
                                                           pClipOffsets,
                                                           qClipOffsets,
                                                           matchList);
                        pairCounts.siftSaved++;
                    } else {
                        LOG.info("generateMatchesForPairs: dropping SIFT matches because coverage is insufficient");
                        pairCounts.siftPoorCoverage++;
                    }

                } else {
                    LOG.info("generateMatchesForPairs: no SIFT matches to save");
                    pairCounts.siftPoorQuantity++;
                }

            } else {

                // GD parameters have been specified
                appendGeometricMatchesIfNecessary(pFeatures,
                                                  qFeatures,
                                                  matchDerivationParameters.matchFullScaleCoverageRadius,
                                                  gdam,
                                                  peakDataCache,
                                                  peakExtractor,
                                                  matchList,
                                                  p,
                                                  q,
                                                  matchResult,
                                                  gdUrlTemplateForRun);
            }
        }

        final int pairCount = renderableCanvasIdPairs.size();

        LOG.info("generateMatchesForPairs: derived matches for {} out of {} pairs", matchList.size(), pairCount);
        LOG.info("generateMatchesForPairs: source cache stats are {}", sourceImageProcessorCache.getStats());
        LOG.info("generateMatchesForPairs: feature cache stats are {}", featureDataCache.stats());
        if (peakDataCache != null) {
            LOG.info("generateMatchesForPairs: peak cache stats are {}", peakDataCache.stats());
        }

        final List<CanvasMatches> nonEmptyMatchesList = storeMatches(matchList);
        this.pairCounts.totalSaved += nonEmptyMatchesList.size();
        this.pairCounts.totalProcessed += pairCount;

        return nonEmptyMatchesList;
    }

    private void appendGeometricMatchesIfNecessary(final CachedCanvasFeatures pCanvasFeatures,
                                                   final CachedCanvasFeatures qCanvasFeatures,
                                                   final double siftFullScaleOverlapBlockRadius,
                                                   final GeometricDescriptorAndMatchFilterParameters gdam,
                                                   final CanvasDataCache peakDataCache,
                                                   final CanvasPeakExtractor peakExtractor,
                                                   final List<CanvasMatches> matchList,
                                                   final CanvasId p,
                                                   final CanvasId q,
                                                   final CanvasMatchResult siftMatchResult,
                                                   final CanvasRenderParametersUrlTemplate gdUrlTemplateForRun) {

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
                                                           matchList);

                } else {

                    LOG.info("appendGeometricMatchesIfNecessary: running Geometric process to improve coverage");

                    findGeometricDescriptorMatches(gdam,
                                                   siftFullScaleOverlapBlockRadius,
                                                   peakDataCache,
                                                   peakExtractor,
                                                   matchList,
                                                   p,
                                                   pCanvasFeatures,
                                                   q,
                                                   qCanvasFeatures,
                                                   siftMatchResult,
                                                   gdUrlTemplateForRun);
                }

            } else if (gdam.runGeoRegardlessOfSiftResults) {

                LOG.info("appendGeometricMatchesIfNecessary: running Geometric process to improve quantity");

                findGeometricDescriptorMatches(gdam,
                                               siftFullScaleOverlapBlockRadius,
                                               peakDataCache,
                                               peakExtractor,
                                               matchList,
                                               p,
                                               pCanvasFeatures,
                                               q,
                                               qCanvasFeatures,
                                               siftMatchResult,
                                               gdUrlTemplateForRun);

            } else {
                LOG.info("appendGeometricMatchesIfNecessary: dropping SIFT matches and skipping Geometric process because only {} matches were found",
                         siftMatchResult.getTotalNumberOfInliers());
                pairCounts.siftPoorQuantity++;
            }


        } else {

            LOG.info("appendGeometricMatchesIfNecessary: running Geometric process since sufficiency constraints are not defined");

            findGeometricDescriptorMatches(gdam,
                                           siftFullScaleOverlapBlockRadius,
                                           peakDataCache,
                                           peakExtractor,
                                           matchList,
                                           p,
                                           pCanvasFeatures,
                                           q,
                                           qCanvasFeatures,
                                           siftMatchResult,
                                           gdUrlTemplateForRun);

        }
    }

    private void findGeometricDescriptorMatches(final GeometricDescriptorAndMatchFilterParameters gdam,
                                                final double siftFullScaleOverlapBlockRadius,
                                                final CanvasDataCache peakDataCache,
                                                final CanvasPeakExtractor peakExtractor,
                                                final List<CanvasMatches> matchList,
                                                final CanvasId p,
                                                final CachedCanvasFeatures pCanvasFeatures,
                                                final CanvasId q,
                                                final CachedCanvasFeatures qCanvasFeatures,
                                                final CanvasMatchResult siftMatchResult,
                                                final CanvasRenderParametersUrlTemplate gdUrlTemplateForRun) {

        final double siftRenderScale = pCanvasFeatures.getRenderParameters().getScale();
        final double[] pClipOffsets = pCanvasFeatures.getClipOffsets();
        final double[] qClipOffsets = qCanvasFeatures.getClipOffsets();

        final GeometricDescriptorParameters gdParameters = gdam.geometricDescriptorParameters;
        final double peakRenderScale = gdam.renderScale;

        final CachedCanvasPeaks pCanvasPeaks =
                peakDataCache.getCanvasPeaks(CanvasIdWithRenderContext.build(p, gdUrlTemplateForRun));
        final CachedCanvasPeaks qCanvasPeaks =
                peakDataCache.getCanvasPeaks(CanvasIdWithRenderContext.build(q, gdUrlTemplateForRun));

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
                        matchList.add(combinedCanvasMatches);

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
            matchList.add(combinedCanvasMatches);
        }

    }

    private List<CanvasMatches> storeMatches(final List<CanvasMatches> allMatchesList)
            throws IOException {

        final List<CanvasMatches> nonEmptyMatchesList =
                allMatchesList.stream().filter(m -> m.size() > 0).collect(Collectors.toList());

        matchStorageClient.saveMatches(nonEmptyMatchesList);

        return nonEmptyMatchesList;
    }

    private void logStats() {
        final int percentSaved = (int) ((pairCounts.totalSaved / (double) pairCounts.totalProcessed) * 100);
        LOG.info("logStats: saved matches for {} out of {} pairs ({}%), siftPoorCoverage: {}, siftPoorQuantity: {}, siftSaved: {}, combinedPoorCoverage: {}, combinedPoorQuantity: {}, combinedSaved: {}, ",
                 pairCounts.totalSaved,
                 pairCounts.totalProcessed,
                 percentSaved,
                 pairCounts.siftPoorCoverage,
                 pairCounts.siftPoorQuantity,
                 pairCounts.siftSaved,
                 pairCounts.combinedPoorCoverage,
                 pairCounts.combinedPoorQuantity,
                 pairCounts.combinedSaved);
    }

    public static CanvasFeatureExtractor getCanvasFeatureExtractor(final FeatureExtractionParameters featureExtraction) {

        final FloatArray2DSIFT.Param siftParameters = new FloatArray2DSIFT.Param();
        siftParameters.fdSize = featureExtraction.fdSize;
        siftParameters.steps = featureExtraction.steps;

        return new CanvasFeatureExtractor(siftParameters,
                                          featureExtraction.minScale,
                                          featureExtraction.maxScale);
    }

    private static final Logger LOG = LoggerFactory.getLogger(SIFTPointMatchClient.class);

    private static Point reScalePoint(final double fromScale,
                                      final Point point,
                                      final double toScale) {
        final double[] reScaledLocal = {
                (point.getL()[0] / fromScale) * toScale,
                (point.getL()[1] / fromScale) * toScale
        };
        return new Point(reScaledLocal);
    }

    private static class MatchPairCounts {
        private long siftPoorCoverage = 0;
        private long siftPoorQuantity = 0;
        private long siftSaved = 0;
        private long combinedPoorCoverage = 0;
        private long combinedPoorQuantity = 0;
        private long combinedSaved = 0;
        private long totalProcessed = 0;
        private long totalSaved = 0;
    }
}

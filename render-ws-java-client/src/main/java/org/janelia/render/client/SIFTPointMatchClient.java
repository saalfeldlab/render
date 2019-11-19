package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import mpicbg.imagefeatures.FloatArray2DSIFT;
import mpicbg.imglib.algorithm.scalespace.DifferenceOfGaussianPeak;
import mpicbg.imglib.type.numeric.real.FloatType;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.match.CanvasFeatureExtractor;
import org.janelia.alignment.match.CanvasFeatureMatcher;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasMatchResult;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.CanvasPeakExtractor;
import org.janelia.alignment.match.CanvasPeakMatcher;
import org.janelia.alignment.match.CanvasRenderParametersUrlTemplate;
import org.janelia.alignment.match.Matches;
import org.janelia.alignment.match.MontageRelativePosition;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.PointMatchQualityStats;
import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.janelia.alignment.match.parameters.FeatureExtractionParameters;
import org.janelia.alignment.match.parameters.FeatureRenderClipParameters;
import org.janelia.alignment.match.parameters.GeometricDescriptorAndMatchFilterParameters;
import org.janelia.alignment.match.parameters.GeometricDescriptorParameters;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.janelia.render.client.cache.CachedCanvasFeatures;
import org.janelia.render.client.cache.CanvasDataCache;
import org.janelia.render.client.cache.CanvasFeatureListLoader;
import org.janelia.render.client.cache.CanvasPeakListLoader;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.FeatureRenderParameters;
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

                if (parameters.geometricDescriptorAndMatch.hasGeometricDescriptorParameters()) {
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

    private long totalProcessed;
    private long totalSaved;

    SIFTPointMatchClient(final Parameters parameters) throws IllegalArgumentException {
        this.parameters = parameters;
        this.matchStorageClient = new RenderDataClient(parameters.matchClient.baseDataUrl,
                                                       parameters.matchClient.owner,
                                                       parameters.matchClient.collection);
        this.totalProcessed = 0;
        this.totalSaved = 0;
    }

    private void generateMatchesForPairFile(final String pairJsonFileName)
            throws IOException, URISyntaxException {

        LOG.info("generateMatchesForPairFile: pairJsonFileName is {}", pairJsonFileName);

        final RenderableCanvasIdPairs renderableCanvasIdPairs = RenderableCanvasIdPairs.load(pairJsonFileName);

        generateMatchesForPairs(renderableCanvasIdPairs,
                                parameters.matchClient.baseDataUrl,
                                parameters.featureRender,
                                parameters.featureRenderClip,
                                parameters.featureExtraction,
                                parameters.featureStorage,
                                parameters.matchDerivation);
    }

    void generateMatchesForPairs(final RenderableCanvasIdPairs renderableCanvasIdPairs,
                                 final String baseDataUrl,
                                 final FeatureRenderParameters featureRenderParameters,
                                 final FeatureRenderClipParameters featureRenderClipParameters,
                                 final FeatureExtractionParameters featureExtractionParameters,
                                 final FeatureStorageParameters featureStorageParameters,
                                 final MatchDerivationParameters matchDerivationParameters)
            throws IOException, URISyntaxException {

        final CanvasRenderParametersUrlTemplate siftUrlTemplateForRun =
                CanvasRenderParametersUrlTemplate.getTemplateForRun(
                        renderableCanvasIdPairs.getRenderParametersUrlTemplate(baseDataUrl),
                        featureRenderParameters.renderFullScaleWidth,
                        featureRenderParameters.renderFullScaleHeight,
                        featureRenderParameters.renderScale,
                        featureRenderParameters.renderWithFilter,
                        featureRenderParameters.renderFilterListName,
                        featureRenderParameters.renderWithoutMask);

        siftUrlTemplateForRun.setClipInfo(featureRenderClipParameters.clipWidth, featureRenderClipParameters.clipHeight);

        final long cacheMaxKilobytes = featureStorageParameters.maxCacheGb * 1000000;
        final CanvasFeatureListLoader featureLoader =
                new CanvasFeatureListLoader(
                        siftUrlTemplateForRun,
                        getCanvasFeatureExtractor(featureExtractionParameters, featureRenderParameters),
                        featureStorageParameters.getRootFeatureDirectory(),
                        featureStorageParameters.requireStoredFeatures);

        final CanvasDataCache dataCache = CanvasDataCache.getSharedCache(cacheMaxKilobytes, featureLoader);
        final CanvasFeatureMatcher featureMatcher = new CanvasFeatureMatcher(matchDerivationParameters);

        final GeometricDescriptorAndMatchFilterParameters gdam = parameters.geometricDescriptorAndMatch;

        final CanvasRenderParametersUrlTemplate gdUrlTemplateForRun;
        final CanvasDataCache peakDataCache;
        final CanvasPeakExtractor peakExtractor;
        if (gdam.hasGeometricDescriptorParameters()) {

            if (CONSENSUS_SETS.equals(matchDerivationParameters.matchFilter)) {
                throw new IllegalArgumentException(
                        "Geometric Descriptor matching is not supported when SIFT matches are grouped into CONSENSUS_SETS");
            }

            gdUrlTemplateForRun = CanvasRenderParametersUrlTemplate.getTemplateForRun(
                    renderableCanvasIdPairs.getRenderParametersUrlTemplate(baseDataUrl),
                    featureRenderParameters.renderFullScaleWidth,
                    featureRenderParameters.renderFullScaleHeight,
                    gdam.renderScale,
                    gdam.renderWithFilter,
                    gdam.renderFilterListName,
                    featureRenderParameters.renderWithoutMask);

            peakExtractor = new CanvasPeakExtractor(gdam.geometricDescriptorParameters);
            final long peakCacheMaxKilobytes = gdam.maxPeakCacheGb * 1000000;

            final CanvasPeakListLoader peakLoader = new CanvasPeakListLoader(gdUrlTemplateForRun, peakExtractor);
            peakDataCache = CanvasDataCache.getSharedCache(peakCacheMaxKilobytes, peakLoader);

        } else {
            gdUrlTemplateForRun = null;
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

            pFeatures = dataCache.getCanvasFeatures(p);
            qFeatures = dataCache.getCanvasFeatures(q);

            LOG.info("generateMatchesForPairs: derive matches between {} and {}", p, q);

            matchResult = featureMatcher.deriveMatchResult(pFeatures.getFeatureList(),
                                                           qFeatures.getFeatureList());

            final double[] pClipOffsets = pFeatures.getClipOffsets();
            final double[] qClipOffsets = qFeatures.getClipOffsets();

            if (peakExtractor == null) {

                // since no GD parameters were specified, simply save SIFT results
                matchResult.addInlierMatchesToList(p.getGroupId(),
                                                   p.getId(),
                                                   q.getGroupId(),
                                                   q.getId(),
                                                   featureRenderParameters.renderScale,
                                                   pClipOffsets,
                                                   qClipOffsets,
                                                   matchList);

            } else {

                // GD parameters have been specified
                appendGeometricMatches(featureRenderParameters,
                                       siftUrlTemplateForRun,
                                       gdam,
                                       gdUrlTemplateForRun,
                                       peakDataCache,
                                       peakExtractor,
                                       matchList,
                                       p,
                                       q,
                                       matchResult,
                                       pClipOffsets,
                                       qClipOffsets);
            }
        }

        final int pairCount = renderableCanvasIdPairs.size();

        LOG.info("generateMatchesForPairs: derived matches for {} out of {} pairs, cache stats are {}",
                 matchList.size(), pairCount, dataCache.stats());

        this.totalSaved += storeMatches(matchList);
        this.totalProcessed += pairCount;
    }

    private void appendGeometricMatches(final FeatureRenderParameters featureRenderParameters,
                                        final CanvasRenderParametersUrlTemplate siftUrlTemplateForRun,
                                        final GeometricDescriptorAndMatchFilterParameters gdam,
                                        final CanvasRenderParametersUrlTemplate gdUrlTemplateForRun,
                                        final CanvasDataCache peakDataCache,
                                        final CanvasPeakExtractor peakExtractor,
                                        final List<CanvasMatches> matchList,
                                        final CanvasId p,
                                        final CanvasId q,
                                        final CanvasMatchResult matchResult,
                                        final double[] pClipOffsets,
                                        final double[] qClipOffsets) {

        if (gdam.hasSufficiencyConstraints()) {


            // check SIFT results to see if GD is needed ...

            // TODO: remove duplicate parameters load (done here and by cache feature loader)
            final RenderParameters pRenderParameters = siftUrlTemplateForRun.getRenderParameters(p);
            final RenderParameters qRenderParameters = siftUrlTemplateForRun.getRenderParameters(q);
            final PointMatchQualityStats siftQualityStats =
                    matchResult.calculateQualityStats(pRenderParameters, qRenderParameters);

            final boolean hasSufficientQuantity =
                    (gdam.sufficientCombinedInliers == null) ||
                    (matchResult.getTotalNumberOfInliers() >= gdam.sufficientCombinedInliers);

            boolean hasSufficientCoverage = true;
            if (gdam.sufficientCombinedCoverageArea != null) {

                hasSufficientCoverage = siftQualityStats.hasSufficientAreaCoverage(gdam.sufficientCombinedCoverageArea);

            } else if (gdam.sufficientCombinedCoverageDistance != null) {

                final MontageRelativePosition relativePosition = p.getRelativePosition();
                if (relativePosition == null) {
                    throw new IllegalArgumentException(
                            "relative canvas position information required to check coverage distance");
                }

                switch (p.getRelativePosition()) {
                    case TOP:
                    case BOTTOM:
                        hasSufficientCoverage =
                                siftQualityStats.hasSufficientWidthCoverage(gdam.sufficientCombinedCoverageDistance);
                        break;
                    case LEFT:
                    case RIGHT:
                        hasSufficientCoverage =
                                siftQualityStats.hasSufficientHeightCoverage(gdam.sufficientCombinedCoverageDistance);
                        break;
                }
            }

            if (hasSufficientQuantity) {

                if (hasSufficientCoverage) {

                    LOG.info("appendGeometricMatches: saving {} SIFT matches and skipping Geometric process",
                             matchResult.getTotalNumberOfInliers());

                    matchResult.addInlierMatchesToList(p.getGroupId(),
                                                       p.getId(),
                                                       q.getGroupId(),
                                                       q.getId(),
                                                       featureRenderParameters.renderScale,
                                                       pClipOffsets,
                                                       qClipOffsets,
                                                       matchList);

                } else {

                    LOG.info("appendGeometricMatches: running Geometric process");

                    findGeometricDescriptorMatches(featureRenderParameters,
                                                   gdam.geometricDescriptorParameters,
                                                   gdam.renderScale,
                                                   gdam.matchDerivationParameters,
                                                   gdUrlTemplateForRun,
                                                   peakDataCache,
                                                   peakExtractor,
                                                   matchList,
                                                   p,
                                                   q,
                                                   matchResult,
                                                   pClipOffsets,
                                                   qClipOffsets);
                }

            } else {
                LOG.info("appendGeometricMatches: dropping SIFT matches and skipping Geometric process because only {} matches were found",
                         matchResult.getTotalNumberOfInliers());
            }


        } else {

            LOG.info("appendGeometricMatches: running Geometric process since sufficiency constraints are not defined");

            findGeometricDescriptorMatches(featureRenderParameters,
                                           gdam.geometricDescriptorParameters,
                                           gdam.renderScale,
                                           gdam.matchDerivationParameters,
                                           gdUrlTemplateForRun,
                                           peakDataCache,
                                           peakExtractor,
                                           matchList,
                                           p,
                                           q,
                                           matchResult,
                                           pClipOffsets,
                                           qClipOffsets);

        }
    }

    private void findGeometricDescriptorMatches(final FeatureRenderParameters featureRenderParameters,
                                                final GeometricDescriptorParameters gdParameters,
                                                final double peakRenderScale,
                                                MatchDerivationParameters gdMatchDerivationParameters,
                                                final CanvasRenderParametersUrlTemplate gdUrlTemplateForRun,
                                                final CanvasDataCache peakDataCache,
                                                final CanvasPeakExtractor peakExtractor,
                                                final List<CanvasMatches> matchList,
                                                final CanvasId p,
                                                final CanvasId q,
                                                final CanvasMatchResult siftMatchResult,
                                                final double[] pClipOffsets,
                                                final double[] qClipOffsets) {

        final List<DifferenceOfGaussianPeak<FloatType>> pCanvasPeaks = peakDataCache.getCanvasPeaks(p).getPeakList();
        final List<DifferenceOfGaussianPeak<FloatType>> qCanvasPeaks = peakDataCache.getCanvasPeaks(q).getPeakList();

        final List<PointMatch> siftInliers = siftMatchResult.getInlierPointMatchList();

        CanvasMatches siftCanvasMatches = null;
        if (siftInliers.size() > 0) {
            final double siftRenderScale = featureRenderParameters.renderScale;
            final List<Point> pInlierPoints = new ArrayList<>();
            final List<Point> qInlierPoints = new ArrayList<>();
            PointMatch.sourcePoints(siftInliers, pInlierPoints);
            PointMatch.targetPoints(siftInliers, qInlierPoints);
            peakExtractor.filterPeaksByInliers(pCanvasPeaks, peakRenderScale, pInlierPoints, siftRenderScale);
            peakExtractor.filterPeaksByInliers(qCanvasPeaks, peakRenderScale, qInlierPoints, siftRenderScale);

            siftMatchResult.addInlierMatchesToList(p.getGroupId(),
                                                   p.getId(),
                                                   q.getGroupId(),
                                                   q.getId(),
                                                   featureRenderParameters.renderScale,
                                                   pClipOffsets,
                                                   qClipOffsets,
                                                   matchList);

            // hack-y: since consensus sets are not supported for GD,
            //         last CanvasMatches object in storage list is the one with the current pair's SIFT matches
            siftCanvasMatches = matchList.get(matchList.size() - 1);
        }

        if (gdMatchDerivationParameters == null) {
            gdMatchDerivationParameters = parameters.matchDerivation;
        }

        final CanvasPeakMatcher peakMatcher = new CanvasPeakMatcher(gdParameters, gdMatchDerivationParameters);
        final CanvasMatchResult gdMatchResult = peakMatcher.deriveMatchResult(pCanvasPeaks, qCanvasPeaks);

        final List<CanvasMatches> gdResults = gdMatchResult.getInlierMatchesList(p.getGroupId(),
                                                                                 p.getId(),
                                                                                 q.getGroupId(),
                                                                                 q.getId(),
                                                                                 peakRenderScale,
                                                                                 pClipOffsets,
                                                                                 qClipOffsets);

        // TODO: derive coverage for SIFT + GD

        for (final CanvasMatches gdCanvasMatches : gdResults) {
            final Matches m = gdCanvasMatches.getMatches();
            if (gdParameters.gdStoredMatchWeight != null) {
                final double[] w = m.getWs();
                if (w != null) {
                    Arrays.fill(w, gdParameters.gdStoredMatchWeight);
                }
            }
            if (siftCanvasMatches == null) {
                matchList.add(gdCanvasMatches);
            } else {
                siftCanvasMatches.append(m);
            }
        }
    }

    private int storeMatches(final List<CanvasMatches> allMatchesList)
            throws IOException {

        final List<CanvasMatches> nonEmptyMatchesList =
                allMatchesList.stream().filter(m -> m.size() > 0).collect(Collectors.toList());

        matchStorageClient.saveMatches(nonEmptyMatchesList);

        return nonEmptyMatchesList.size();
    }

    private void logStats() {
        final int percentSaved = (int) ((totalSaved / (double) totalProcessed) * 100);
        LOG.info("logStats: saved matches for {} out of {} pairs ({}%)",
                 totalSaved, totalProcessed, percentSaved);
    }

    public static CanvasFeatureExtractor getCanvasFeatureExtractor(final FeatureExtractionParameters featureExtraction,
                                                                    final FeatureRenderParameters featureRender) {

        final FloatArray2DSIFT.Param siftParameters = new FloatArray2DSIFT.Param();
        siftParameters.fdSize = featureExtraction.fdSize;
        siftParameters.steps = featureExtraction.steps;

        return new CanvasFeatureExtractor(siftParameters,
                                          featureExtraction.minScale,
                                          featureExtraction.maxScale,
                                          featureRender.fillWithNoise);
    }

    private static final Logger LOG = LoggerFactory.getLogger(SIFTPointMatchClient.class);
}

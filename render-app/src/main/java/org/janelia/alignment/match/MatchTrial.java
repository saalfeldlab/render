package org.janelia.alignment.match;

import java.io.Serializable;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;
import mpicbg.imglib.algorithm.scalespace.DifferenceOfGaussianPeak;
import mpicbg.imglib.type.numeric.real.FloatType;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URIBuilder;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.match.parameters.FeatureAndMatchParameters;
import org.janelia.alignment.match.parameters.FeatureExtractionParameters;
import org.janelia.alignment.match.parameters.FeatureRenderClipParameters;
import org.janelia.alignment.match.parameters.GeometricDescriptorAndMatchFilterParameters;
import org.janelia.alignment.match.parameters.GeometricDescriptorParameters;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.janelia.alignment.match.parameters.MatchTrialParameters;
import org.janelia.alignment.util.ImageProcessorCache;

/**
 * Encapsulates all information for a match trial.
 *
 * @author Eric Trautman
 */
public class MatchTrial implements Serializable {

    private String id;
    private final MatchTrialParameters parameters;
    private List<Matches> matches;
    private MatchTrialStats stats;
    private MatchTrialStats gdStats;

    @SuppressWarnings("unused")
    MatchTrial() {
        this(null);
    }

    public MatchTrial(final MatchTrialParameters parameters) {
        this.parameters = parameters;
    }

    public String getId() {
        return id;
    }

    public MatchTrialParameters getParameters() {
        return parameters;
    }

    public List<Matches> getMatches() {
        return matches;
    }

    public void setMatches(final List<Matches> matches) {
        this.matches = matches;
    }

    public MatchTrialStats getStats() {
        return stats;
    }

    public void setStats(final MatchTrialStats stats) {
        this.stats = stats;
    }

    public MatchTrial getCopyWithId(final String id) {
        final MatchTrial copy = new MatchTrial(parameters);
        copy.id = id;
        copy.matches = this.matches;
        copy.stats = this.stats;
        copy.gdStats = this.gdStats;
        return copy;
    }

    public void deriveResults()
            throws IllegalArgumentException {

        final FeatureAndMatchParameters featureAndMatchParameters = parameters.getFeatureAndMatchParameters();
        final FeatureRenderClipParameters clipParameters = featureAndMatchParameters.getClipParameters();
        final GeometricDescriptorAndMatchFilterParameters gdmfParameters =
                parameters.getGeometricDescriptorAndMatchFilterParameters();

        MontageRelativePosition pClipPosition = null;
        MontageRelativePosition qClipPosition = null;

        if ((clipParameters != null) &&
            ((clipParameters.clipWidth != null) || (clipParameters.clipHeight != null))) {
            pClipPosition = featureAndMatchParameters.getpClipPosition();
            qClipPosition = pClipPosition.getOpposite();
        }

        final String groupId = "trial";
        final FeatureExtractionParameters siftFeatureParameters = featureAndMatchParameters.getSiftFeatureParameters();

        final long pFeatureStart = System.currentTimeMillis();

        final CanvasData pCanvasData = new CanvasData(parameters.getpRenderParametersUrl(),
                                                      new CanvasId(groupId, "P", pClipPosition),
                                                      clipParameters,
                                                      gdmfParameters);
        final List<Feature> pFeatureList = pCanvasData.extractFeatures(siftFeatureParameters);

        final long qFeatureStart = System.currentTimeMillis();

        final CanvasData qCanvasData = new CanvasData(parameters.getqRenderParametersUrl(),
                                                      new CanvasId(groupId, "Q", qClipPosition),
                                                      clipParameters,
                                                      gdmfParameters);
        final List<Feature> qFeatureList = qCanvasData.extractFeatures(siftFeatureParameters);

        if (! pCanvasData.getSiftRenderScale().equals(qCanvasData.getSiftRenderScale())) {
            throw new IllegalArgumentException(
                    "render scales for both canvases must be the same but pCanvas scale is " +
                    pCanvasData.getSiftRenderScale() + " while qCanvas render scale is " +
                    qCanvasData.getSiftRenderScale());
        }

        final long matchStart = System.currentTimeMillis();

        final MatchDerivationParameters siftMatchDerivationParameters =
                featureAndMatchParameters.getMatchDerivationParameters();

        final CanvasFeatureMatcher matcher = new CanvasFeatureMatcher(siftMatchDerivationParameters);
        final CanvasMatchResult siftMatchResult = matcher.deriveMatchResult(pFeatureList, qFeatureList);

        final long matchStop = System.currentTimeMillis();

        final CanvasId pCanvasId = pCanvasData.getCanvasId();
        final CanvasId qCanvasId = qCanvasData.getCanvasId();
        final List<CanvasMatches> siftResults = siftMatchResult.getInlierMatchesList(pCanvasId.getGroupId(),
                                                                                     pCanvasId.getId(),
                                                                                     qCanvasId.getGroupId(),
                                                                                     qCanvasId.getId(),
                                                                                     pCanvasData.getSiftRenderScale(),
                                                                                     pCanvasId.getClipOffsets(),
                                                                                     qCanvasId.getClipOffsets());
        this.matches = new ArrayList<>(siftResults.size());
        for (final CanvasMatches canvasMatches : siftResults) {
            final Matches m = canvasMatches.getMatches();
            this.matches.add(m);
        }

        if (gdmfParameters != null) {

            // run Geometric Descriptor matching using the SIFT result inliers (if they exist) for masking
            gdmfParameters.gdEnabled = true;

            final List<PointMatch> inliersSIFT = siftMatchResult.getInlierPointMatchList();
            final List<Point> pInlierPoints = new ArrayList<>();
            final List<Point> qInlierPoints = new ArrayList<>();

            if (inliersSIFT.size() > 0) {
                PointMatch.sourcePoints(inliersSIFT, pInlierPoints);
                PointMatch.targetPoints(inliersSIFT, qInlierPoints);
            }

            final GeometricDescriptorParameters gdParameters = gdmfParameters.geometricDescriptorParameters;

            final double peakRenderScale = gdmfParameters.renderScale;

            final long pPeakStart = System.currentTimeMillis();
            final List<DifferenceOfGaussianPeak<FloatType>> pCanvasPeaks =
                    pCanvasData.extractPeaks(gdParameters, pInlierPoints);
            final long qPeakStart = System.currentTimeMillis();
            final List<DifferenceOfGaussianPeak<FloatType>> qCanvasPeaks =
                    qCanvasData.extractPeaks(gdParameters, qInlierPoints);

            final long gdMatchStart = System.currentTimeMillis();
            final MatchDerivationParameters gdMatchDerivationParameters = gdmfParameters.matchDerivationParameters;

            final CanvasPeakMatcher peakMatcher = new CanvasPeakMatcher(gdParameters, gdMatchDerivationParameters);
            final CanvasMatchResult gdMatchResult = peakMatcher.deriveMatchResult(pCanvasPeaks, qCanvasPeaks);

            final long gdMatchStop = System.currentTimeMillis();

            final List<CanvasMatches> gdResults = gdMatchResult.getInlierMatchesList(pCanvasId.getGroupId(),
                                                                                     pCanvasId.getId(),
                                                                                     qCanvasId.getGroupId(),
                                                                                     qCanvasId.getId(),
                                                                                     peakRenderScale,
                                                                                     pCanvasId.getClipOffsets(),
                                                                                     qCanvasId.getClipOffsets());

            final PointMatchQualityStats gdQualityStats =
                    gdMatchResult.calculateQualityStats(pCanvasData.peakRenderParameters,
                                                        pCanvasData.peakImageProcessorWithMasks.mask,
                                                        qCanvasData.peakRenderParameters,
                                                        qCanvasData.peakImageProcessorWithMasks.mask,
                                                        siftMatchDerivationParameters.matchFullScaleCoverageRadius);
            
            this.gdStats = new MatchTrialStats(pCanvasPeaks.size(),
                                               (qPeakStart - pPeakStart),
                                               qCanvasPeaks.size(),
                                               (gdMatchStart - qPeakStart),
                                               gdMatchResult.getConsensusSetSizes(),
                                               (gdMatchStop - gdMatchStart),
                                               gdQualityStats);

            for (final CanvasMatches canvasMatches : gdResults) {
                final Matches m = canvasMatches.getMatches();
                if ((gdParameters.gdStoredMatchWeight != null) && (m.w != null)) {
                    Arrays.fill(m.w, gdParameters.gdStoredMatchWeight);
                }
                this.matches.add(m);
            }
        }

        final PointMatchQualityStats siftQualityStats =
                siftMatchResult.calculateQualityStats(pCanvasData.siftRenderParameters,
                                                      pCanvasData.siftImageProcessorWithMasks.mask,
                                                      qCanvasData.siftRenderParameters,
                                                      qCanvasData.siftImageProcessorWithMasks.mask,
                                                      siftMatchDerivationParameters.matchFullScaleCoverageRadius);

        this.stats = new MatchTrialStats(pFeatureList.size(),
                                         (qFeatureStart - pFeatureStart),
                                         qFeatureList.size(),
                                         (matchStart - qFeatureStart),
                                         siftMatchResult.getConsensusSetSizes(),
                                         (matchStop - matchStart),
                                         siftQualityStats);
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    public static MatchTrial fromJson(final String json) {
        return JSON_HELPER.fromJson(json);
    }

    private static final JsonUtils.Helper<MatchTrial> JSON_HELPER =
            new JsonUtils.Helper<>(MatchTrial.class);

    /**
     * Helper class to hold data for each rendered canvas.
     */
    private static class CanvasData {

        private final CanvasIdWithRenderContext siftCanvasIdWithRenderContext;
        private RenderParameters siftRenderParameters;
        private ImageProcessorWithMasks siftImageProcessorWithMasks;
        private final CanvasIdWithRenderContext peakCanvasIdWithRenderContext;
        private RenderParameters peakRenderParameters;
        private ImageProcessorWithMasks peakImageProcessorWithMasks;

        CanvasData(final String renderParametersUrl,
                   final CanvasId canvasId,
                   final FeatureRenderClipParameters clipParameters,
                   final GeometricDescriptorAndMatchFilterParameters gdmfParameters)
                throws IllegalArgumentException {

            final Integer clipWidth;
            final Integer clipHeight;
            if (clipParameters == null) {
                clipWidth = null;
                clipHeight = null;
            } else {
                clipWidth = clipParameters.clipWidth;
                clipHeight = clipParameters.clipHeight;
            }

            this.siftCanvasIdWithRenderContext =
                    new CanvasIdWithRenderContext(canvasId,
                                                  "feature_0",
                                                  renderParametersUrl,
                                                  clipWidth,
                                                  clipHeight);

            this.siftRenderParameters = null;
            this.siftImageProcessorWithMasks = null;

            if (gdmfParameters != null) {

                final String peakRenderParametersUrl;
                try {
                    final URIBuilder builder = new URIBuilder(renderParametersUrl);
                    final List<NameValuePair> queryParams = builder.getQueryParams();
                    final String filterListNameKey = "filterListName";
                    final String fillWithNoiseKey = "fillWithNoise";
                    queryParams.removeIf(pair -> (filterListNameKey.equals(pair.getName()) ||
                                                  (fillWithNoiseKey.equals(pair.getName()))) );
                    builder.setParameters(queryParams);
                    builder.setParameter("scale", String.valueOf(gdmfParameters.renderScale));

                    builder.setParameter("filter", String.valueOf((gdmfParameters.renderWithFilter != null) &&
                                                                  gdmfParameters.renderWithFilter));
                    if (gdmfParameters.renderFilterListName != null) {
                        builder.setParameter(filterListNameKey, gdmfParameters.renderFilterListName);
                    }
                    peakRenderParametersUrl = builder.toString();
               } catch (final URISyntaxException e) {
                    throw new IllegalStateException(
                            "failed to create peak render parameters URL from " + renderParametersUrl, e);
                }

                this.peakCanvasIdWithRenderContext =
                        new CanvasIdWithRenderContext(canvasId,
                                                      "peak_0",
                                                      peakRenderParametersUrl,
                                                      clipWidth,
                                                      clipHeight);
            } else {
                this.peakCanvasIdWithRenderContext = null;
            }

            this.peakRenderParameters = null;
            this.peakImageProcessorWithMasks = null;
        }

        public CanvasId getCanvasId() {
            return siftCanvasIdWithRenderContext.getCanvasId();
        }

        public Double getSiftRenderScale() {
            return siftRenderParameters.getScale();
        }

        List<Feature> extractFeatures(final FeatureExtractionParameters siftFeatureParameters) {

            final FloatArray2DSIFT.Param siftParameters = new FloatArray2DSIFT.Param();
            siftParameters.fdSize = siftFeatureParameters.fdSize;
            siftParameters.steps = siftFeatureParameters.steps;

            final CanvasFeatureExtractor extractor = new CanvasFeatureExtractor(siftParameters,
                                                                                siftFeatureParameters.minScale,
                                                                                siftFeatureParameters.maxScale);

            siftRenderParameters = siftCanvasIdWithRenderContext.loadRenderParameters();
            siftImageProcessorWithMasks = Renderer.renderImageProcessorWithMasks(siftRenderParameters,
                                                                                 ImageProcessorCache.DISABLED_CACHE);

            return extractor.extractFeaturesFromImageAndMask(siftImageProcessorWithMasks.ip,
                                                             siftImageProcessorWithMasks.mask);
        }

        List<DifferenceOfGaussianPeak<FloatType>> extractPeaks(final GeometricDescriptorParameters gdParameters,
                                                               final List<Point> siftInlierPoints) {

            final CanvasPeakExtractor peakExtractor = new CanvasPeakExtractor(gdParameters);
            peakRenderParameters = peakCanvasIdWithRenderContext.loadRenderParameters();
            final double peakRenderScale = peakRenderParameters.getScale();

            peakImageProcessorWithMasks = Renderer.renderImageProcessorWithMasks(peakRenderParameters,
                                                                                 ImageProcessorCache.DISABLED_CACHE);

            final List<DifferenceOfGaussianPeak<FloatType>> canvasPeaks =
                    peakExtractor.extractPeaksFromImageAndMask(peakImageProcessorWithMasks.ip,
                                                               peakImageProcessorWithMasks.mask);

            if (siftInlierPoints.size() > 0) {
                final double siftRenderScale = getSiftRenderScale();
                peakExtractor.filterPeaksByInliers(canvasPeaks, peakRenderScale, siftInlierPoints, siftRenderScale);
            }

            return peakExtractor.nonMaximalSuppression(canvasPeaks, peakRenderScale);
        }

        @Override
        public String toString() {
            return getCanvasId().getId();
        }

    }

}

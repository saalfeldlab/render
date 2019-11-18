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

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URIBuilder;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.match.parameters.FeatureAndMatchParameters;
import org.janelia.alignment.match.parameters.FeatureExtractionParameters;
import org.janelia.alignment.match.parameters.FeatureRenderClipParameters;
import org.janelia.alignment.match.parameters.GeometricDescriptorAndMatchFilterParameters;
import org.janelia.alignment.match.parameters.GeometricDescriptorParameters;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.janelia.alignment.match.parameters.MatchTrialParameters;
import org.janelia.alignment.spec.Bounds;

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
    private GeometricDescriptorMatchStats gdStats;

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

        MontageRelativePosition pClipPosition = null;
        MontageRelativePosition qClipPosition = null;

        if ((clipParameters != null) &&
            ((clipParameters.clipWidth != null) || (clipParameters.clipHeight != null))) {

            pClipPosition = featureAndMatchParameters.getpClipPosition();

            switch (pClipPosition) {
                case TOP: qClipPosition = MontageRelativePosition.BOTTOM; break;
                case BOTTOM: qClipPosition = MontageRelativePosition.TOP; break;
                case LEFT: qClipPosition = MontageRelativePosition.RIGHT; break;
                case RIGHT: qClipPosition = MontageRelativePosition.LEFT; break;
            }

        }

        final String groupId = "trial";
        final FeatureExtractionParameters siftFeatureParameters = featureAndMatchParameters.getSiftFeatureParameters();

        final long pFeatureStart = System.currentTimeMillis();

        final CanvasData pCanvasData = new CanvasData(parameters.getpRenderParametersUrl(),
                                                      new CanvasId(groupId, "P", pClipPosition),
                                                      clipParameters);
        final List<Feature> pFeatureList = pCanvasData.extractFeatures(siftFeatureParameters);

        final long qFeatureStart = System.currentTimeMillis();

        final CanvasData qCanvasData = new CanvasData(parameters.getqRenderParametersUrl(),
                                                      new CanvasId(groupId, "Q", qClipPosition),
                                                      clipParameters);
        final List<Feature> qFeatureList = qCanvasData.extractFeatures(siftFeatureParameters);

        if (pCanvasData.siftRenderScale != qCanvasData.siftRenderScale) {
            throw new IllegalArgumentException(
                    "render scales for both canvases must be the same but pCanvas scale is " +
                    pCanvasData.siftRenderScale + " while qCanvas render scale is " +
                    qCanvasData.siftRenderScale);
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
                                                                                     pCanvasData.siftRenderScale,
                                                                                     pCanvasId.getClipOffsets(),
                                                                                     qCanvasId.getClipOffsets());
        this.matches = new ArrayList<>(siftResults.size());
        for (final CanvasMatches canvasMatches : siftResults) {
            final Matches m = canvasMatches.getMatches();
            this.matches.add(m);
        }


        if (parameters.hasGeometricDescriptorAndMatchFilterParameters()) {

            // run Geometric Descriptor matching using the SIFT result inliers (if they exist) for masking

            final GeometricDescriptorAndMatchFilterParameters gdmfParameters =
                    parameters.getGeometricDescriptorAndMatchFilterParameters();

            final List<PointMatch> inliersSIFT = siftMatchResult.getInlierPointMatchList();
            final List<Point> pInlierPoints = new ArrayList<>();
            final List<Point> qInlierPoints = new ArrayList<>();

            if (inliersSIFT.size() > 0) {
                PointMatch.sourcePoints(inliersSIFT, pInlierPoints);
                PointMatch.targetPoints(inliersSIFT, qInlierPoints);
            }

            final GeometricDescriptorParameters gdParameters = gdmfParameters.getGeometricDescriptorParameters();

            final double peakRenderScale = gdmfParameters.getRenderScale();

            final long pPeakStart = System.currentTimeMillis();
            final List<DifferenceOfGaussianPeak<FloatType>> pCanvasPeaks =
                    pCanvasData.extractPeaks(gdParameters,
                                             peakRenderScale,
                                             gdmfParameters.getRenderWithFilter(),
                                             gdmfParameters.getRenderFilterListName(),
                                             pInlierPoints);
            final long qPeakStart = System.currentTimeMillis();
            final List<DifferenceOfGaussianPeak<FloatType>> qCanvasPeaks =
                    qCanvasData.extractPeaks(gdParameters,
                                             peakRenderScale,
                                             gdmfParameters.getRenderWithFilter(),
                                             gdmfParameters.getRenderFilterListName(),
                                             qInlierPoints);

            final long gdMatchStart = System.currentTimeMillis();
            final MatchDerivationParameters gdMatchDerivationParameters =
                    gdmfParameters.hasMatchDerivationParameters() ?
                    gdmfParameters.getMatchDerivationParameters() : siftMatchDerivationParameters;

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
                                                        qCanvasData.peakRenderParameters);
            this.gdStats = new GeometricDescriptorMatchStats(pCanvasPeaks.size(),
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
                                                      qCanvasData.siftRenderParameters);

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

        private final String siftRenderParametersUrl;
        private final RenderParameters siftRenderParameters;
        private RenderParameters peakRenderParameters;
        private final double siftRenderScale;
        private final CanvasId canvasId;
        private final FeatureRenderClipParameters clipParameters;

        CanvasData(final String renderParametersUrl,
                   final CanvasId canvasId,
                   final FeatureRenderClipParameters clipParameters)
                throws IllegalArgumentException {

            this.siftRenderParametersUrl = renderParametersUrl;
            this.siftRenderParameters = RenderParameters.loadFromUrl(renderParametersUrl);
            this.siftRenderScale = this.siftRenderParameters.getScale();
            this.peakRenderParameters = null;
            this.canvasId = canvasId;
            this.clipParameters = clipParameters;

            this.applyClipIfNecessary(canvasId, siftRenderParameters, clipParameters);
        }

        public CanvasId getCanvasId() {
            return canvasId;
        }

        List<Feature> extractFeatures(final FeatureExtractionParameters siftFeatureParameters) {

            final FloatArray2DSIFT.Param siftParameters = new FloatArray2DSIFT.Param();
            siftParameters.fdSize = siftFeatureParameters.fdSize;
            siftParameters.steps = siftFeatureParameters.steps;

            final CanvasFeatureExtractor extractor = new CanvasFeatureExtractor(siftParameters,
                                                                                siftFeatureParameters.minScale,
                                                                                siftFeatureParameters.maxScale,
                                                                                siftRenderParameters.isFillWithNoise());

            return extractor.extractFeatures(siftRenderParameters, null);
        }

        List<DifferenceOfGaussianPeak<FloatType>> extractPeaks(final GeometricDescriptorParameters gdParameters,
                                                               final double peakRenderScale,
                                                               final Boolean renderWithFilter,
                                                               final String renderFilterListName,
                                                               final List<Point> siftInlierPoints) {

            final CanvasPeakExtractor peakExtractor = new CanvasPeakExtractor(gdParameters);

            try {
                final URIBuilder builder = new URIBuilder(siftRenderParametersUrl);
                final List<NameValuePair> queryParams = builder.getQueryParams();
                final String filterListNameKey = "filterListName";
                final String fillWithNoiseKey = "fillWithNoise";
                queryParams.removeIf(pair -> (filterListNameKey.equals(pair.getName()) ||
                                              (fillWithNoiseKey.equals(pair.getName()))) );
                builder.setParameters(queryParams);
                builder.setParameter("scale", String.valueOf(peakRenderScale));
                builder.setParameter("filter", String.valueOf((renderWithFilter != null) && renderWithFilter));
                if (renderFilterListName != null) {
                    builder.setParameter(filterListNameKey, renderFilterListName);
                }
                peakRenderParameters = RenderParameters.loadFromUrl(builder.toString());
            } catch (final URISyntaxException e) {
                throw new IllegalStateException(
                        "failed to create peak render parameters URL from " + siftRenderParametersUrl, e);
            }

            this.applyClipIfNecessary(canvasId, peakRenderParameters, clipParameters);

            final List<DifferenceOfGaussianPeak<FloatType>> canvasPeaks =
                    peakExtractor.extractPeaks(peakRenderParameters, null);

            if (siftInlierPoints.size() > 0) {
                peakExtractor.filterPeaksByInliers(canvasPeaks, peakRenderScale, siftInlierPoints, siftRenderScale);
            }

            return peakExtractor.nonMaximalSuppression(canvasPeaks, peakRenderScale);
        }

        @Override
        public String toString() {
            return canvasId.getId();
        }

        private void applyClipIfNecessary(final CanvasId canvasId,
                                          final RenderParameters renderParameters,
                                          final FeatureRenderClipParameters clipParameters) {
            if (canvasId.getRelativePosition() != null) {
                final Bounds bounds = new Bounds(renderParameters.x,
                                                 renderParameters.y,
                                                 renderParameters.x + renderParameters.width,
                                                 renderParameters.y + renderParameters.height);

                final Integer clipWidth = clipParameters.clipWidth;
                final Integer clipHeight = clipParameters.clipHeight;

                canvasId.setClipOffsets((int) bounds.getDeltaX(), (int) bounds.getDeltaY(), clipWidth, clipHeight);
                renderParameters.clipForMontagePair(canvasId, clipWidth, clipHeight);
            }

        }

    }

}

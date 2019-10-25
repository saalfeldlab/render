package org.janelia.alignment.match;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.match.parameters.FeatureAndMatchParameters;
import org.janelia.alignment.match.parameters.FeatureExtractionParameters;
import org.janelia.alignment.match.parameters.FeatureRenderClipParameters;
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

    @SuppressWarnings("unused")
    public MatchTrial() {
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
        copy.stats =this.stats;
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

        final long pFeatureStart = System.currentTimeMillis();
        final String groupId = "trial";

        final CanvasData pCanvasData = new CanvasData(parameters.getpRenderParametersUrl(),
                                                      new CanvasId(groupId, "P", pClipPosition),
                                                      parameters);

        final long qFeatureStart = System.currentTimeMillis();

        final CanvasData qCanvasData = new CanvasData(parameters.getqRenderParametersUrl(),
                                                      new CanvasId(groupId, "Q", qClipPosition),
                                                      parameters);

        if (pCanvasData.getRenderScale() - qCanvasData.getRenderScale() != 0.0) {
            throw new IllegalArgumentException(
                    "render scales for both canvases must be the same but pCanvas scale is " +
                    pCanvasData.getRenderScale() + " while qCanvas render scale is " + qCanvasData.getRenderScale());
        }

        final long matchStart = System.currentTimeMillis();

        final MatchDerivationParameters matchDerivationParameters =
                featureAndMatchParameters.getMatchDerivationParameters();

        final CanvasFeatureMatcher matcher = new CanvasFeatureMatcher(matchDerivationParameters);

        final CanvasFeatureMatchResult matchResult =
                matcher.deriveMatchResult(pCanvasData.getFeatureList(), qCanvasData.getFeatureList());

        final long matchStop = System.currentTimeMillis();

        final CanvasId pCanvasId = pCanvasData.getCanvasId();
        final CanvasId qCanvasId = qCanvasData.getCanvasId();
        final List<CanvasMatches> results = matchResult.getInlierMatchesList(pCanvasId.getGroupId(),
                                                                             pCanvasId.getId(),
                                                                             qCanvasId.getGroupId(),
                                                                             qCanvasId.getId(),
                                                                             pCanvasData.getRenderScale(),
                                                                             pCanvasId.getClipOffsets(),
                                                                             qCanvasId.getClipOffsets());
        this.matches = new ArrayList<>(results.size());
        for (final CanvasMatches canvasMatches : results) {
            final Matches m = canvasMatches.getMatches();
            this.matches.add(m);
        }

        final PointMatchQualityStats pointMatchQualityStats = matchResult.calculateQualityStats();
        final double[] aggregateDeltaXAndYStandardDeviation =
                pointMatchQualityStats.getAggregateDeltaXAndYStandardDeviation();

        this.stats = new MatchTrialStats(pCanvasData.featureList.size(),
                                         (qFeatureStart - pFeatureStart),
                                         qCanvasData.featureList.size(),
                                         (matchStart - qFeatureStart),
                                         matchResult.getConsensusSetSizes(),
                                         (matchStop - matchStart),
                                         aggregateDeltaXAndYStandardDeviation[0],
                                         aggregateDeltaXAndYStandardDeviation[1],
                                         pointMatchQualityStats.getConsensusSetDeltaXStandardDeviations(),
                                         pointMatchQualityStats.getConsensusSetDeltaYStandardDeviations());
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
     * Helper class to hold data (render parameters, features, etc.) for each canvas.
     */
    private static class CanvasData {

        private final RenderParameters renderParameters;
        private final CanvasId canvasId;
        private final List<Feature> featureList;

        CanvasData(final String renderParametersUrl,
                   final CanvasId canvasId,
                   final MatchTrialParameters trialParameters)
                throws IllegalArgumentException {

            this.renderParameters = RenderParameters.loadFromUrl(renderParametersUrl);
            this.canvasId = canvasId;

            if (canvasId.getRelativePosition() != null) {

                final Bounds bounds = new Bounds(renderParameters.x,
                                                 renderParameters.y,
                                                 renderParameters.x + renderParameters.width,
                                                 renderParameters.y + renderParameters.height);

                final FeatureRenderClipParameters clipParameters =
                        trialParameters.getFeatureAndMatchParameters().getClipParameters();
                final Integer clipWidth = clipParameters.clipWidth;
                final Integer clipHeight = clipParameters.clipHeight;

                canvasId.setClipOffsets((int) bounds.getDeltaX(), (int) bounds.getDeltaY(), clipWidth, clipHeight);
                renderParameters.clipForMontagePair(canvasId, clipWidth, clipHeight);

            }

            final FeatureExtractionParameters siftFeatureParameters =
                    trialParameters.getFeatureAndMatchParameters().getSiftFeatureParameters();

            final FloatArray2DSIFT.Param siftParameters = new FloatArray2DSIFT.Param();
            siftParameters.fdSize = siftFeatureParameters.fdSize;
            siftParameters.steps = siftFeatureParameters.steps;

            final CanvasFeatureExtractor extractor = new CanvasFeatureExtractor(siftParameters,
                                                                                siftFeatureParameters.minScale,
                                                                                siftFeatureParameters.maxScale,
                                                                                renderParameters.isFillWithNoise());

            this.featureList = extractor.extractFeatures(renderParameters, null);
        }

        public CanvasId getCanvasId() {
            return canvasId;
        }

        List<Feature> getFeatureList() {
            return featureList;
        }

        public Double getRenderScale() {
            return renderParameters.getScale();
        }

        @Override
        public String toString() {
            return canvasId.getId();
        }

    }

}

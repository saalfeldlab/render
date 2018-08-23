package org.janelia.alignment.match;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;

import org.janelia.alignment.RenderParameters;
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

    private final String id;
    private final MatchTrialParameters parameters;
    private List<Matches> matches;
    private MatchTrialStats stats;

    public MatchTrial(final String id,
                      final MatchTrialParameters parameters) {
        this.id = id;
        this.parameters = parameters;
    }

    public List<Matches> getMatches() {
        return matches;
    }

    public MatchTrialStats getStats() {
        return stats;
    }

    public void deriveResults() {

        final FeatureRenderClipParameters clipParameters =
                parameters.getFeatureAndMatchParameters().getFeatureRenderClipParameters();

        MontageRelativePosition pPosition = null;
        MontageRelativePosition qPosition = null;

        if ((clipParameters != null) &&
            ((clipParameters.clipWidth != null) || (clipParameters.clipHeight != null))) {

            pPosition = parameters.getpPosition();
            if (pPosition == null) {
                throw new IllegalArgumentException("pPosition must be specified for clipping");
            }

            switch (pPosition) {
                case TOP: qPosition = MontageRelativePosition.BOTTOM; break;
                case BOTTOM: qPosition = MontageRelativePosition.TOP; break;
                case LEFT: qPosition = MontageRelativePosition.RIGHT; break;
                case RIGHT: qPosition = MontageRelativePosition.LEFT; break;
            }

        }

        final long pFeatureStart = System.currentTimeMillis();

        final CanvasData pCanvasData = new CanvasData(parameters.getpUrl(),
                                                      new CanvasId(id, "P", pPosition),
                                                      parameters);

        final long qFeatureStart = System.currentTimeMillis();

        final CanvasData qCanvasData = new CanvasData(parameters.getqUrl(),
                                                      new CanvasId(id, "Q", qPosition),
                                                      parameters);

        final long matchStart = System.currentTimeMillis();

        final MatchDerivationParameters matchDerivationParameters =
                parameters.getFeatureAndMatchParameters().getMatchDerivationParameters();

        final CanvasFeatureMatcher matcher = new CanvasFeatureMatcher(matchDerivationParameters.matchRod,
                                                                      matchDerivationParameters.matchModelType,
                                                                      matchDerivationParameters.matchIterations,
                                                                      matchDerivationParameters.matchMaxEpsilon,
                                                                      matchDerivationParameters.matchMinInlierRatio,
                                                                      matchDerivationParameters.matchMinNumInliers,
                                                                      matchDerivationParameters.matchMaxTrust,
                                                                      matchDerivationParameters.matchMaxNumInliers,
                                                                      matchDerivationParameters.matchFilter);

        final CanvasFeatureMatchResult matchResult =
                matcher.deriveMatchResult(pCanvasData.getFeatureList(), qCanvasData.getFeatureList());

        final long matchStop = System.currentTimeMillis();

        final CanvasId pCanvasId = pCanvasData.getCanvasId();
        final CanvasId qCanvasId = qCanvasData.getCanvasId();
        final List<CanvasMatches> results = matchResult.getInlierMatchesList(pCanvasId.getGroupId(),
                                                                             pCanvasId.getId(),
                                                                             qCanvasId.getGroupId(),
                                                                             qCanvasId.getId(),
                                                                             1.0,
                                                                             pCanvasId.getClipOffsets(),
                                                                             qCanvasId.getClipOffsets());
        this.matches = new ArrayList<>(results.size());
        for (final CanvasMatches canvasMatches : results) {
            this.matches.add(canvasMatches.getMatches());
        }

        this.stats = new MatchTrialStats(pCanvasData.featureList.size(),
                                         (qFeatureStart - pFeatureStart),
                                         qCanvasData.featureList.size(),
                                         (matchStart - qFeatureStart),
                                         matchResult.getConsensusSetSizes(),
                                         (matchStop - matchStart));
    }

    /**
     * Helper class to hold data (render parameters, features, etc.) for each canvas.
     */
    private static class CanvasData {

        private final RenderParameters renderParameters;
        private final CanvasId canvasId;
        private final List<Feature> featureList;

        CanvasData(final String canvasUrl,
                   final CanvasId canvasId,
                   final MatchTrialParameters trialParameters)
                throws IllegalArgumentException {

            this.renderParameters = RenderParameters.loadFromUrl(canvasUrl);
            this.canvasId = canvasId;

            if (canvasId.getRelativePosition() != null) {

                final Bounds bounds = new Bounds(renderParameters.x,
                                                 renderParameters.y,
                                                 renderParameters.x + renderParameters.width,
                                                 renderParameters.y + renderParameters.height);

                final FeatureRenderClipParameters clipParameters =
                        trialParameters.getFeatureAndMatchParameters().getFeatureRenderClipParameters();
                final Integer clipWidth = clipParameters.clipWidth;
                final Integer clipHeight = clipParameters.clipHeight;

                canvasId.setClipOffsets((int) bounds.getDeltaX(), (int) bounds.getDeltaY(), clipWidth, clipHeight);
                renderParameters.clipForMontagePair(canvasId, clipWidth, clipHeight);

            }

            final FeatureExtractionParameters featureExtractionParameters =
                    trialParameters.getFeatureAndMatchParameters().getFeatureExtractionParameters();

            final FloatArray2DSIFT.Param siftParameters = new FloatArray2DSIFT.Param();
            siftParameters.fdSize = featureExtractionParameters.fdSize;
            siftParameters.steps = featureExtractionParameters.steps;

            final CanvasFeatureExtractor extractor = new CanvasFeatureExtractor(siftParameters,
                                                                                featureExtractionParameters.minScale,
                                                                                featureExtractionParameters.maxScale,
                                                                                trialParameters.getFillWithNoise());

            this.featureList = extractor.extractFeatures(renderParameters, null);
        }

        public CanvasId getCanvasId() {
            return canvasId;
        }

        List<Feature> getFeatureList() {
            return featureList;
        }

        @Override
        public String toString() {
            return canvasId.getId();
        }

    }

}

package org.janelia.alignment.match;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import mpicbg.ij.FeatureTransform;
import mpicbg.imagefeatures.Feature;
import mpicbg.models.PointMatch;
import mpicbg.util.Timer;

import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Derives point matches between the features of two canvases, filtering out outlier matches.
 * Core logic stolen from Stephan Saalfeld <saalfelds@janelia.hhmi.org>.
 *
 * @author Eric Trautman
 */
public class CanvasFeatureMatcher implements Serializable {

    private final float rod;
    private final MatchFilter matchFilter;

    /**
     * Sets up everything that is needed to derive point matches from the feature lists of two canvases.
     */
    public CanvasFeatureMatcher(final MatchDerivationParameters matchParameters) {
        this.rod = matchParameters.matchRod;
        this.matchFilter = new MatchFilter(matchParameters);
    }

    public MatchFilter getMatchFilter() {
        return matchFilter;
    }

    /**
     * @param  canvas1Features  feature list for first canvas.
     * @param  canvas2Features  feature list for second canvas.
     *
     * @return SIFT match results for the specified feature lists.
     */
    public CanvasMatchResult deriveMatchResult(final List<Feature> canvas1Features,
                                               final List<Feature> canvas2Features) {

        LOG.info("deriveMatchResult: entry, canvas1Features.size={}, canvas2Features.size={}",
                 canvas1Features.size(), canvas2Features.size());

        final Timer timer = new Timer();
        timer.start();

        final List<PointMatch> candidates = new ArrayList<>(canvas1Features.size());

        FeatureTransform.matchFeatures(canvas1Features, canvas2Features, candidates, rod);

        final CanvasMatchResult result = matchFilter.buildMatchResult(candidates);

        LOG.info("deriveMatchResult: exit, result={}, elapsedTime={}s", result, (timer.stop() / 1000));

        return result;
    }

    private static final Logger LOG = LoggerFactory.getLogger(CanvasFeatureMatcher.class);
}

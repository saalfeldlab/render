package org.janelia.alignment.match;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import mpicbg.ij.FeatureTransform;
import mpicbg.imagefeatures.Feature;
import mpicbg.models.PointMatch;
import mpicbg.util.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Derives SIFT point matches between the features of two canvases.
 * Core logic stolen from Stephan Saalfeld <saalfelds@janelia.hhmi.org>.
 *
 * @author Eric Trautman
 */
public class CanvasSiftFeatureMatcher
        implements Serializable {

    private final float rod;

    /**
     * Sets up everything that is needed to derive SIFT point matches from the feature lists of two canvases.
     *
     * @param  rod              ratio of distances (e.g. 0.92f).
     */
    public CanvasSiftFeatureMatcher(final float rod) {
        this.rod = rod;
    }

    /**
     * @param  canvas1Features  feature list for first canvas.
     * @param  canvas2Features  feature list for second canvas.
     * @param  matchFilter      filter for matches.
     *
     * @return match results for this canvas pair.
     */
    public CanvasFeatureMatchResult deriveMatchResult(final List<Feature> canvas1Features,
                                                      final List<Feature> canvas2Features,
                                                      final CanvasMatchFilter matchFilter) {

        LOG.info("deriveMatchResult: entry, canvas1Features.size={}, canvas2Features.size={}",
                 canvas1Features.size(), canvas2Features.size());

        final Timer timer = new Timer();
        timer.start();

        final List<PointMatch> candidates = new ArrayList<>(canvas1Features.size());

        FeatureTransform.matchFeatures(canvas1Features, canvas2Features, candidates, rod);

        final CanvasFeatureMatchResult result = matchFilter.getFilteredCanvasFeatureMatchResult(candidates);

        LOG.info("deriveMatchResult: exit, result={}, elapsedTime={}s", result, (timer.stop() / 1000));

        return result;
    }

    private static final Logger LOG = LoggerFactory.getLogger(CanvasSiftFeatureMatcher.class);
}

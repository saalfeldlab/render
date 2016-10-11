package org.janelia.alignment.match;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import mpicbg.ij.FeatureTransform;
import mpicbg.imagefeatures.Feature;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;
import mpicbg.trakem2.transform.AffineModel2D;
import mpicbg.util.Timer;

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
    private final float maxEpsilon;
    private final float minInlierRatio;
    private final int minNumInliers;
    private final Integer maxNumInliers;
    private final boolean filterMatches;

    /**
     * Sets up everything that is needed to derive point matches from the feature lists of two canvases.
     *
     * @param  rod             ratio of distances (e.g. 0.92f).
     * @param  maxEpsilon      minimal allowed transfer error (e.g. 20.0f).
     * @param  minInlierRatio  minimal ratio of inliers to candidates (e.g. 0.0f).
     * @param  minNumInliers   minimal absolute number of inliers for matches (e.g. 10).
     * @param  maxNumInliers   (optional) maximum number of inliers for matches; null indicates no maximum.
     * @param  filterMatches   indicates whether matches should be filtered.
     */
    public CanvasFeatureMatcher(final float rod,
                                final float maxEpsilon,
                                final float minInlierRatio,
                                final int minNumInliers,
                                final Integer maxNumInliers,
                                final boolean filterMatches) {
        this.rod = rod;
        this.maxEpsilon = maxEpsilon;
        this.minInlierRatio = minInlierRatio;
        this.minNumInliers = minNumInliers;
        this.maxNumInliers = maxNumInliers;
        this.filterMatches = filterMatches;
    }

    public boolean isFilterMatches() {
        return filterMatches;
    }

    /**
     * @param  canvas1Features  feature list for first canvas.
     * @param  canvas2Features  feature list for second canvas.
     *
     * @return match results for the specified feature lists.
     */
    public CanvasFeatureMatchResult deriveMatchResult(final List<Feature> canvas1Features,
                                                      final List<Feature> canvas2Features) {

        LOG.info("deriveMatchResult: entry, canvas1Features.size={}, canvas2Features.size={}",
                 canvas1Features.size(), canvas2Features.size());

        final Timer timer = new Timer();
        timer.start();

        final AffineModel2D model = new AffineModel2D();
        final List<PointMatch> candidates = new ArrayList<>(canvas1Features.size());

        FeatureTransform.matchFeatures(canvas1Features, canvas2Features, candidates, rod);

        final List<PointMatch> inliers;
        if (filterMatches) {
            inliers = filterMatches(candidates, model);
        } else {
            inliers = candidates;
        }

        final Double inlierRatio;
        if (candidates.size() > 0) {
            inlierRatio = (double) inliers.size() / candidates.size();
        } else {
            inlierRatio = 0.0;
        }

        final CanvasFeatureMatchResult result =
                new CanvasFeatureMatchResult(inliers.size() > 0,
                                             model,
                                             inliers,
                                             inlierRatio);

        LOG.info("deriveMatchResult: exit, result={}, elapsedTime={}s", result, (timer.stop() / 1000));

        return result;
    }

    public List<PointMatch> filterMatches(final List<PointMatch> candidates,
                                          final Model model) {

        final ArrayList<PointMatch> inliers = new ArrayList<>(candidates.size());

        if (candidates.size() > 0) {
            try {
                model.filterRansac(candidates,
                                   inliers,
                                   1000,
                                   maxEpsilon,
                                   minInlierRatio,
                                   minNumInliers,
                                   3);
            } catch (final NotEnoughDataPointsException e) {
                LOG.warn("failed to filter outliers", e);
            }

            if ((maxNumInliers != null) && (maxNumInliers > 0) && (inliers.size() > maxNumInliers)) {
                LOG.info("filterMatches: randomly selecting {} of {} inliers", maxNumInliers, inliers.size());
                // randomly select maxNumInliers elements by shuffling and then remove excess elements
                Collections.shuffle(inliers);
                inliers.subList(maxNumInliers, inliers.size()).clear();
            }

        }

        LOG.info("filterMatches: filtered {} inliers from {} candidates", inliers.size(), candidates.size());

        return inliers;
    }

    public Matches filterMatches(final Matches candidates,
                                 final Model model,
                                 final double renderScale) {

        final List<PointMatch> candidatesList =
                CanvasFeatureMatchResult.convertMatchesToPointMatchList(candidates);
        final List<PointMatch> inliersList = filterMatches(candidatesList, model);
        return CanvasFeatureMatchResult.convertPointMatchListToMatches(inliersList, renderScale);
    }

    private static final Logger LOG = LoggerFactory.getLogger(CanvasFeatureMatcher.class);
}

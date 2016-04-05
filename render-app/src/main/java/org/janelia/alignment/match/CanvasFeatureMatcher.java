package org.janelia.alignment.match;

import java.util.ArrayList;
import java.util.List;

import mpicbg.ij.FeatureTransform;
import mpicbg.imagefeatures.Feature;
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
public class CanvasFeatureMatcher {

    private final float rod;
    private final float maxEpsilon;
    private final float minInlierRatio;
    private final int minNumInliers;

    /**
     * Sets up everything that is needed to derive point matches from the feature lists of two canvases.
     *
     * @param  rod             ratio of distances (e.g. 0.92f).
     * @param  maxEpsilon      minimal allowed transfer error (e.g. 20.0f).
     * @param  minInlierRatio  minimal ratio of inliers to candidates (e.g. 0.0f).
     * @param  minNumInliers   minimal absolute number of inliers for matches (e.g. 10).
     */
    public CanvasFeatureMatcher(final float rod,
                                final float maxEpsilon,
                                final float minInlierRatio,
                                final int minNumInliers) {
        this.rod = rod;
        this.maxEpsilon = maxEpsilon;
        this.minInlierRatio = minInlierRatio;
        this.minNumInliers = minNumInliers;
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
        final ArrayList<PointMatch> inliers = new ArrayList<>();

        final ArrayList<PointMatch> candidates = new ArrayList<>();

        FeatureTransform.matchFeatures(canvas1Features, canvas2Features, candidates, rod);

        boolean modelFound = false;
        try {
            modelFound = model.filterRansac(candidates,
                                            inliers,
                                            1000,
                                            maxEpsilon,
                                            minInlierRatio,
                                            minNumInliers,
                                            3);
        } catch (final NotEnoughDataPointsException e) {
            LOG.warn("failed to filter outliers", e);
        }

        LOG.info("deriveMatchResult: filtered {} inliers from {} candidates", inliers.size(), candidates.size());

        final Double inlierRatio;
        if (modelFound) {
            inlierRatio = (double) inliers.size() / candidates.size();
        } else {
            inlierRatio = 0.0;
        }

        final CanvasFeatureMatchResult result =
                new CanvasFeatureMatchResult(modelFound,
                                             model,
                                             inliers,
                                             inlierRatio);

        LOG.info("deriveMatchResult: exit, result={}, elapsedTime={}s", result, (timer.stop() / 1000));

        return result;
    }


    private static final Logger LOG = LoggerFactory.getLogger(CanvasFeatureMatcher.class);
}

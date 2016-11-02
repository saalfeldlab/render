package org.janelia.alignment.match;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.util.Timer;

import org.ddogleg.struct.FastQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import boofcv.abst.feature.associate.AssociateDescription;
import boofcv.abst.feature.associate.ScoreAssociation;
import boofcv.factory.feature.associate.FactoryAssociation;
import boofcv.struct.feature.AssociatedIndex;
import boofcv.struct.feature.BrightFeature;
import georegression.struct.point.Point2D_F64;

/**
 * Derives SURF point matches between the features of two canvases, filtering out outlier matches.
 *
 * @author Eric Trautman
 */
public class CanvasSurfFeatureMatcher
        implements Serializable {

    /**
     * @param  canvas1Features  feature list for first canvas.
     * @param  canvas2Features  feature list for second canvas.
     * @param  matchFilter      filter for matches.

     * @return match results for this canvas pair.
     */
    public CanvasFeatureMatchResult deriveMatchResult(final SurfFeatures canvas1Features,
                                                      final SurfFeatures canvas2Features,
                                                      final CanvasMatchFilter matchFilter) {

        LOG.info("deriveMatchResult: entry, canvas1Features.size={}, canvas2Features.size={}",
                 canvas1Features.size(), canvas2Features.size());

        final Timer timer = new Timer();
        timer.start();

        final List<PointMatch> candidates = new ArrayList<>(canvas1Features.size());

        // Associate features between the two images
        // Detect using the standard SURF feature descriptor and describer

        final ScoreAssociation<BrightFeature> scorer = FactoryAssociation.scoreEuclidean(BrightFeature.class, true);
        final AssociateDescription<BrightFeature> associate = FactoryAssociation.greedy(scorer, 2, true);

        associate.setSource(canvas1Features.getFeatureDescriptors());
        associate.setDestination(canvas2Features.getFeatureDescriptors());
        associate.associate();

        // create a list of AssociatedPairs that tell the model matcher how a feature moved
        final FastQueue<AssociatedIndex> matches = associate.getMatches();

        final List<Point2D_F64> pointsA = canvas1Features.getPoints();
        final List<Point2D_F64> pointsB = canvas2Features.getPoints();

        Point2D_F64 a;
        Point2D_F64 b;
        for( int i = 0; i < matches.size(); i++ ) {
            final AssociatedIndex match = matches.get(i);

            a = pointsA.get(match.src);
            b = pointsB.get(match.dst);

            candidates.add(new PointMatch(new Point(new double[] {a.x, a.y}),
                                          new Point(new double[] {b.x, b.y})));
        }

        final CanvasFeatureMatchResult result = matchFilter.getFilteredCanvasFeatureMatchResult(candidates);

        LOG.info("deriveMatchResult: exit, result={}, elapsedTime={}s", result, (timer.stop() / 1000));

        return result;
    }

    private static final Logger LOG = LoggerFactory.getLogger(CanvasSurfFeatureMatcher.class);

}

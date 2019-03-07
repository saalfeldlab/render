package org.janelia.alignment.util;

import java.util.ArrayList;
import java.util.List;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;

/**
 * Utilities stolen from Stephan's Fiji scripts for use in render.
 *
 * @author Stephan Saalfeld
 */
public class ScriptUtil {

    /**
     * Fits sampled points to a model.
     *
     * Stolen from
     *
     * <a href="https://github.com/axtimwalde/fiji-scripts/blob/master/TrakEM2/visualize-ct-difference.bsh#L90-L106">
     *     https://github.com/axtimwalde/fiji-scripts/blob/master/TrakEM2/visualize-ct-difference.bsh#L90-L106
     * </a>.
     *
     * @param  model                model to fit (note: model will be changed by this operation).
     * @param  coordinateTransform  transform to apply to each sampled point.
     * @param  sampleWidth          width of each sample.
     * @param  sampleHeight         height of each sample.
     * @param  samplesPerDimension  number of samples to take in each dimension.
     */
    public static void fit(final Model<?> model,
                           final CoordinateTransform coordinateTransform,
                           final double sampleWidth,
                           final double sampleHeight,
                           final int samplesPerDimension)
            throws NotEnoughDataPointsException, IllDefinedDataPointsException {

        final List<PointMatch> matches = new ArrayList<>();

        for (int y = 0; y < samplesPerDimension; ++y) {
            final double sampleY = y * sampleHeight;
            for (int x = 0; x < samplesPerDimension; ++x) {
                final double sampleX = x * sampleWidth;
                final Point p = new Point(new double[]{sampleX, sampleY});
                p.apply(coordinateTransform);
                matches.add(new PointMatch(p, p));
            }
        }

        model.fit(matches);
    }

    /**
     * Applies two transforms to a set of sampled locations and calculates the distance between the results
     * for each location.  The maximum distance between all sample locations is returned.
     *
     * Stolen from
     *
     * <a href="https://github.com/axtimwalde/fiji-scripts/blob/master/TrakEM2/visualize-ct-difference.bsh#L56-L72">
     *     https://github.com/axtimwalde/fiji-scripts/blob/master/TrakEM2/visualize-ct-difference.bsh#L56-L72
     * </a>.
     *
     * @param  coordinateTransform1  first transform to apply to all sampled points.
     * @param  coordinateTransform2  comparison transform to apply to all sampled points.
     * @param  sampleWidth           width of each sample.
     * @param  sampleHeight          height of each sample.
     * @param  samplesPerDimension   number of samples to take in each dimension.
     *
     * @return maximum delta between transformations of all sample locations.
     */
    public static double calculateMaxDelta(final CoordinateTransform coordinateTransform1,
                                           final CoordinateTransform coordinateTransform2,
                                           final double sampleWidth,
                                           final double sampleHeight,
                                           final int samplesPerDimension) {

        double maxDelta = 0.0;

        for (int y = 0; y < samplesPerDimension; ++y) {
            final double sampleY = y * sampleHeight;
            for (int x = 0; x < samplesPerDimension; ++x) {
                final double sampleX = x * sampleWidth;
                final double[] l1 = new double[]{sampleX, sampleY};
                final double[] l2 = new double[]{sampleX, sampleY};
                coordinateTransform1.applyInPlace(l1);
                coordinateTransform2.applyInPlace(l2);
                final double dx = l1[0] - l2[0];
                final double dy = l1[1] - l2[1];
                final double d = Math.sqrt((dx * dx) + (dy * dy));
                maxDelta = Math.max(maxDelta, d);
            }
        }

        return maxDelta;
    }

}

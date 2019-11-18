package org.janelia.alignment.match;

import ij.gui.PolygonRoi;
import ij.gui.Roi;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import mpicbg.models.Point;

/**
 * Utilities for convex hull calculation taken from:
 *
 * <a href="https://www.geeksforgeeks.org/convex-hull-set-1-jarviss-algorithm-or-wrapping/">
 *     https://www.geeksforgeeks.org/convex-hull-set-1-jarviss-algorithm-or-wrapping/
 * </a>
 *
 * @author Stephan Preibisch
 */
public class ConvexHull {

    /**
     * @return list of convex hull points.
     */
    public static List<Point> deriveConvexHull(final List<Point> points)
            throws IllegalArgumentException {

        final int size = points.size();

        // There must be at least 3 points
        if (size < 3) {
            throw new IllegalArgumentException("To calculate convex hull, point list must contain at least 3 points.");
        }

        // Initialize Result
        final List<Point> hull = new ArrayList<>();

        // Find the leftmost point
        int l = 0;
        for (int i = 1; i < size; i++) {
            if (points.get(i).getL()[0] < points.get(l).getL()[0]) {
                l = i;
            }
        }

        // Start from leftmost point, keep moving counterclockwise until reach the start point again.
        // This loop runs O(h) times where h is number of points in result or output.
        int p = l, q;
        do {
            // Add current point to result
            hull.add(points.get(p));

            // Search for a point 'q' such that orientation(p, x, q) is counterclockwise for all points 'x'.
            // The idea is to keep track of last visited most counter-clockwise point in q.
            // If any point 'i' is more counter-clockwise than q, then update q.
            q = (p + 1) % size;

            for (int i = 0; i < size; i++) {
                // If i is more counter-clockwise than current q, then update q
                if (getOrientation(points.get(p), points.get(i), points.get(q)) == 2) {
                    q = i;
                }
            }

            // Now q is the most counter-clockwise with respect to p. Set p as q for next iteration,
            // so that q is added to result 'hull'.
            p = q;

        } while (p != l);  // While we don't come to first point

        return hull;
    }

    public static PolygonRoi createPolygon(final List<Point> points) {
        final float[] xPoints = new float[points.size()];
        final float[] yPoints = new float[points.size()];

        for (int i = 0; i < xPoints.length; ++i) {
            xPoints[i] = (float) points.get(i).getL()[0];
            yPoints[i] = (float) points.get(i).getL()[1];
        }

        return new PolygonRoi(xPoints, yPoints, Roi.POLYGON);
    }

    // from: https://www.mathopenref.com/coordpolygonarea2.html
    public static double calculatePolygonArea(final List<Point> points) {
        final int numPoints = points.size();
        double area = 0;         // Accumulates area in the loop
        int j = numPoints - 1;  // The last vertex is the 'previous' one to the first

        for (int i = 0; i < numPoints; i++) {
            area += (points.get(j).getL()[0] + points.get(i).getL()[0]) *
                    (points.get(j).getL()[1] - points.get(i).getL()[1]);
            j = i;  //j is previous vertex to i
        }

        return Math.abs(area / 2);
    }

    //TODO: proper center of mass computation
    public static double[] calculateCenterOfMassBruteForce(final PolygonRoi roi) {
        final Rectangle r = roi.getBounds();

        long cx = 0, cy = 0;
        long count = 0;

        for (int y = r.y; y < r.height + r.y; ++y) {
            for (int x = r.x; x < r.width + r.x; ++x) {
                if (roi.contains(x, y)) {
                    cx += x;
                    cy += y;
                    ++count;
                }
            }
        }

        return new double[]{(double) cx / (double) count, (double) cy / (double) count};
    }

    /**
     * @return orientation of ordered triplet (p, q, r).
     *         0 --> p, q and r are co-linear
     *         1 --> clockwise
     *         2 --> counter-clockwise
     */
    private static int getOrientation(final Point p,
                                      final Point q,
                                      final Point r) {
        final double val = (q.getL()[1] - p.getL()[1]) * (r.getL()[0] - q.getL()[0]) -
                           (q.getL()[0] - p.getL()[0]) * (r.getL()[1] - q.getL()[1]);

        if (val == 0) {
            return 0;  // collinear
        }
        return (val > 0) ? 1 : 2; // clockwise or counter-clockwise
    }

}

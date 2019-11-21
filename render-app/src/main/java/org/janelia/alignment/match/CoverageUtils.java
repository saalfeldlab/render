package org.janelia.alignment.match;

import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.process.ImageProcessor;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import mpicbg.models.Model;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;

import net.imglib2.KDTree;
import net.imglib2.RealPoint;
import net.imglib2.neighborsearch.NearestNeighborSearchOnKDTree;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;

/**
 * Utilities for point match coverage calculations.
 *
 * @author Stephan Preibisch
 */
@SuppressWarnings("WeakerAccess")
public class CoverageUtils {

    /**
     * @return {@link ValuePair} of SIFT "covered" pixels and total overlap pixels.
     *
     * @throws IllegalArgumentException
     *   if the specified matches are insufficient to align the image pair.
     */
    public static Pair<Long, Long> computeOverlappingCoverage(final ImageProcessor imageP,
                                                              final ImageProcessor maskP,
                                                              final ImageProcessor imageQ,
                                                              final ImageProcessor maskQ,
                                                              final List<PointMatch> inliersSIFT,
                                                              final Model<?> model,
                                                              final double blockRadiusSIFT)
            throws IllegalArgumentException {
        return computeOverlappingCoverage(imageP, imageP.getWidth(), imageP.getHeight(), maskP,
                                          imageQ, imageQ.getWidth(), imageQ.getHeight(), maskQ,
                                          inliersSIFT, model, blockRadiusSIFT);
    }

    /**
     * @return {@link ValuePair} of SIFT "covered" pixels and total overlap pixels.
     *
     * @throws IllegalArgumentException
     *   if the specified matches are insufficient to align the image pair.
     */
    public static Pair<Long, Long> computeOverlappingCoverage(final int widthP,
                                                              final int heightP,
                                                              final ImageProcessor maskP,
                                                              final int widthQ,
                                                              final int heightQ,
                                                              final ImageProcessor maskQ,
                                                              final List<PointMatch> inliersSIFT,
                                                              final Model<?> model,
                                                              final double blockRadiusSIFT)
            throws IllegalArgumentException {

        return computeOverlappingCoverage(null, widthP, heightP, maskP,
                                          null, widthQ, heightQ, maskQ,
                                          inliersSIFT, model, blockRadiusSIFT);
    }

    @SuppressWarnings("unused")
    private static Pair<Long, Long> computeOverlappingCoverage(final ImageProcessor imageP,
                                                               final int widthP,
                                                               final int heightP,
                                                               final ImageProcessor maskP,
                                                               final ImageProcessor imageQ,
                                                               final int widthQ,
                                                               final int heightQ,
                                                               final ImageProcessor maskQ,
                                                               final List<PointMatch> inliersSIFT,
                                                               final Model<?> model,
                                                               final double blockRadiusSIFT)
            throws IllegalArgumentException {

        // debug
        //ImagePlus impP = new ImagePlus( "p", new FloatProcessor( imageP.getWidth(), imageP.getHeight()  ));
        //ImagePlus impQ = new ImagePlus( "q", new FloatProcessor( imageP.getWidth(), imageP.getHeight()  ));
        //ImagePlus impPO = new ImagePlus( "poverlap", new FloatProcessor( imageP.getWidth(), imageP.getHeight()  ));

        try {
            model.fit(inliersSIFT);
        } catch (final Exception e) {
            throw new IllegalArgumentException("matches are insufficient for aligning pair", e);
        }

        final List<Point> inlierPointsP = new ArrayList<>();
        PointMatch.sourcePoints(inliersSIFT, inlierPointsP);

        final List<RealPoint> realPointListP = new ArrayList<>();

        for (final Point p : inlierPointsP) {
            realPointListP.add(new RealPoint(p.getL()[0], p.getL()[1]));
        }

        final NearestNeighborSearchOnKDTree<RealPoint> nnP =
                new NearestNeighborSearchOnKDTree<>(new KDTree<>(realPointListP, realPointListP));

        final double[] tmp = new double[2];
        long totalOverlap = 0;
        long coveredBySIFT = 0;

        for (int y = 0; y < heightP; ++y) {
            for (int x = 0; x < widthP; ++x) {
                //impP.getProcessor().setf( x, y, imageP.getf( x, y ) );

                // is inside the mask of P
                if ((maskP == null) || (maskP.getf(x, y) > 0)) {
                    tmp[0] = x;
                    tmp[1] = y;
                    model.applyInPlace(tmp);

                    if (tmp[0] >= 0 && tmp[0] <= widthQ - 1 &&
                        tmp[1] >= 0 && tmp[1] <= heightQ - 1 &&
                        ( (maskQ == null) ||
                          (maskQ.getf((int) Math.round(tmp[0]), (int) Math.round(tmp[1])) > 0) )) {
                        //impQ.getProcessor().setf( x, y, imageQ.getf( (int)Math.round( tmp[ 0 ] ), (int)Math.round( tmp[ 1 ] ) ) );
                        //impPO.getProcessor().setf( x, y, 1 );

                        // is inside Q and inside the mask of Q
                        ++totalOverlap;

                        // test if covered by SIFT
                        final RealPoint p = new RealPoint(x, y);
                        nnP.search(p);

                        // first nearest neighbor is the point itself, we need the second nearest
                        final double d = nnP.getDistance();

                        if (d <= blockRadiusSIFT) {
                            ++coveredBySIFT;
                            //impPO.getProcessor().setf( x, y, impPO.getProcessor().getf( x, y ) + 1 );
                        }
                    }
                }
            }
        }

        //impP.show();
        //impQ.show();
        //impPO.show();

        return new ValuePair<>(coveredBySIFT, totalOverlap);
    }

    /**
     * Computes convex hull perimeter from the specified point list.
     *
     * Logic was adapted from:
     * <a href="https://www.geeksforgeeks.org/convex-hull-set-1-jarviss-algorithm-or-wrapping/">
     *     https://www.geeksforgeeks.org/convex-hull-set-1-jarviss-algorithm-or-wrapping/
     * </a>
     *
     * @return list of convex hull points.
     */
    @SuppressWarnings("unused")
    static List<Point> computeConvexHull(final List<Point> points)
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

    static PolygonRoi createPolygon(final List<Point> points) {
        final float[] xPoints = new float[points.size()];
        final float[] yPoints = new float[points.size()];

        for (int i = 0; i < xPoints.length; ++i) {
            xPoints[i] = (float) points.get(i).getL()[0];
            yPoints[i] = (float) points.get(i).getL()[1];
        }

        return new PolygonRoi(xPoints, yPoints, Roi.POLYGON);
    }

    // from: https://www.mathopenref.com/coordpolygonarea2.html
    static double calculatePolygonArea(final List<Point> points) {
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
    static double[] calculateCenterOfMassBruteForce(final PolygonRoi roi) {
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

package org.janelia.alignment.util;

import ij.ImagePlus;
import ij.gui.PointRoi;

import java.util.ArrayList;
import java.util.List;

import mpicbg.imglib.algorithm.scalespace.DifferenceOfGaussianPeak;
import mpicbg.imglib.type.numeric.real.FloatType;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;

import net.imglib2.KDTree;
import net.imglib2.RealPoint;
import net.imglib2.neighborsearch.NearestNeighborSearchOnKDTree;

/**
 * Utility methods for debugging images using ImageJ/Fiji user interface.
 *
 * @author Stephan Preibisch
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class ImageDebugUtil {

    public static void drawBlockedRegions(final List<Point> inlierPointList,
                                          final double radius,
                                          final ImagePlus imagePlus) {
        // assemble the list of points (we need two lists as the KDTree sorts the list)
        // we assume that the order of list2 is preserved
        final List<RealPoint> realPointList = new ArrayList<>();

        for (final Point p : inlierPointList) {
            realPointList.add(new RealPoint(p.getL()[0], p.getL()[1]));
        }

        // make the KDTree
        final KDTree<RealPoint> tree = new KDTree<>(realPointList, realPointList);

        // Nearest neighbor for each point, populate the new list
        final NearestNeighborSearchOnKDTree<RealPoint> nn = new NearestNeighborSearchOnKDTree<>(tree);

        for (int y = 0; y < imagePlus.getHeight(); ++y) {
            for (int x = 0; x < imagePlus.getWidth(); ++x) {
                final RealPoint p = new RealPoint(x, y);
                nn.search(p);

                // first nearest neighbor is the point itself, we need the second nearest
                final double d = nn.getDistance();

                if (d <= radius) {
                    imagePlus.getProcessor().set(x, y, 255);
                }
            }
        }
    }

    public static void setPointMatchRois(final List<PointMatch> pointMatchList,
                                         final ImagePlus pImagePlus,
                                         final ImagePlus qImagePlus) {
        final List<Point> pInlierPoints = new ArrayList<>();
        PointMatch.sourcePoints(pointMatchList, pInlierPoints);
        setPointRois(pInlierPoints, pImagePlus);

        final List<Point> qInlierPoints = new ArrayList<>();
        PointMatch.targetPoints(pointMatchList, qInlierPoints);
        setPointRois(qInlierPoints, qImagePlus);
    }

    public static void setPointRois(final List<Point> pointList,
                                    final ImagePlus imagePlus) {
        final PointRoi points = mpicbg.ij.util.Util.pointsToPointRoi(pointList);
        imagePlus.setRoi(points);
    }

    public static void setPeakPointRois(final List<DifferenceOfGaussianPeak<FloatType>> peakList,
                                        final ImagePlus imagePlus) {
        final List<Point> pointList = new ArrayList<>();
        for (final DifferenceOfGaussianPeak<FloatType> p : peakList) {
            pointList.add(new Point(new double[]{p.getSubPixelPosition(0), p.getSubPixelPosition(1)}));
        }
        setPointRois(pointList, imagePlus);
    }

}

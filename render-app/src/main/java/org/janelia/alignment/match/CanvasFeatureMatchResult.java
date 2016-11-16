package org.janelia.alignment.match;

import java.util.ArrayList;
import java.util.List;

import mpicbg.models.Model;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;

/**
 * Encapsulates key data elements from canvas feature match derivation.
 *
 * @author Eric Trautman
 */
public class CanvasFeatureMatchResult {

    private final boolean modelFound;
    private final Model model;
    private final List<PointMatch> inliers;
    private final Double inlierRatio;

    public CanvasFeatureMatchResult(final boolean modelFound,
                                    final Model model,
                                    final List<PointMatch> inliers,
                                    final Double inlierRatio) {
        this.inlierRatio = inlierRatio;
        this.model = model;
        this.modelFound = modelFound;
        this.inliers = inliers;
    }

    /**
     * @return true if a filter model could be estimated; otherwise false.
     */
    public boolean isModelFound() {
        return modelFound;
    }

    /**
     * @return estimated filter model.
     */
    public Model getModel() {
        return model;
    }

    /**
     * @return collection of inlier matches.
     */
    public List<PointMatch> getInlierPointMatchList() {
        return inliers;
    }

    /**
     * @param renderScale     scale of rendered canvases (needed to return matches in full scale coordinates).
     *
     * @return collection of inlier matches.
     */
    public Matches getInlierMatches(final Double renderScale) {
        return convertPointMatchListToMatches(inliers, renderScale);
    }

    /**
     * @return ratio of the number of inlier matches to the number of total candidates.
     */
    public Double getInlierRatio() {
        return inlierRatio;
    }

    @Override
    public String toString() {
        return "{modelFound: " + isModelFound() +
               "', inlierCount: " + getInlierPointMatchList().size() +
               ", inlierRatio: " + getInlierRatio() +
               '}';
    }

    /**
     * @param  pointMatchList  list of point matches to convert.
     * @param  renderScale     scale of rendered canvases (needed to return matches in full scale coordinates).
     *
     * @return the specified point match list in {@link Matches} form.
     */
    public static Matches convertPointMatchListToMatches(final List<PointMatch> pointMatchList,
                                                         final double renderScale) {

        final Matches matches;

        final int pointMatchCount = pointMatchList.size();

        if (pointMatchCount > 0) {

            PointMatch pointMatch = pointMatchList.get(0);
            Point p1 = pointMatch.getP1();
            double[] local1 = p1.getL();
            final int dimensionCount = local1.length;

            final double p[][] = new double[dimensionCount][pointMatchCount];
            final double q[][] = new double[dimensionCount][pointMatchCount];
            final double w[] = new double[pointMatchCount];

            Point p2;
            double[] local2;
            for (int i = 0; i < pointMatchCount; i++) {

                pointMatch = pointMatchList.get(i);

                p1 = pointMatch.getP1();
                local1 = p1.getL();

                p2 = pointMatch.getP2();
                local2 = p2.getL();

                for (int j = 0; j < dimensionCount; j++) {
                    if (renderScale == 1.0) {
                        p[j][i] = local1[j];
                        q[j][i] = local2[j];
                    } else {
                        // point matches must be stored in full scale coordinates
                        p[j][i] = local1[j] / renderScale;
                        q[j][i] = local2[j] / renderScale;
                    }
                }

                w[i] = pointMatch.getWeight();

            }

            matches = new Matches(p, q, w);

        } else {
            matches = new Matches(new double[1][0], new double[1][0], new double[0]);
        }


        return matches;
    }

    /**
     * @param  matches  point match list in {@link Matches} form.
     *
     * @return the corresponding list of {@link PointMatch} objects.
     */
    public static List<PointMatch> convertMatchesToPointMatchList(final Matches matches) {

        final double w[] = matches.getWs();

        final int pointMatchCount = w.length;
        final List<PointMatch> pointMatchList = new ArrayList<>(pointMatchCount);

        if (pointMatchCount > 0) {
            final double p[][] = matches.getPs();
            final double q[][] = matches.getQs();

            final int dimensionCount = p.length;

            for (int matchIndex = 0; matchIndex < pointMatchCount; matchIndex++) {

                final double pLocal[] = new double[dimensionCount];
                final double qLocal[] = new double[dimensionCount];

                for (int dimensionIndex = 0; dimensionIndex < dimensionCount; dimensionIndex++) {
                    pLocal[dimensionIndex] = p[dimensionIndex][matchIndex];
                    qLocal[dimensionIndex] = q[dimensionIndex][matchIndex];
                }

                pointMatchList.add(new PointMatch(new Point(pLocal), new Point(qLocal), w[matchIndex]));
            }
        }

        return pointMatchList;
    }
}

package org.janelia.alignment.match;

import java.util.List;

import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.trakem2.transform.AffineModel2D;

/**
 * Encapsulates key data elements from canvas feature match derivation.
 *
 * @author Eric Trautman
 */
public class CanvasFeatureMatchResult {

    private final boolean modelFound;
    private final AffineModel2D model;
    private final List<PointMatch> inliers;
    private final Double inlierRatio;

    public CanvasFeatureMatchResult(final boolean modelFound,
                                    final AffineModel2D model,
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
    public AffineModel2D getModel() {
        return model;
    }

    /**
     * @return collection of inlier matches.
     */
    public List<PointMatch> getInlierPointMatchList() {
        return inliers;
    }

    /**
     * @return collection of inlier matches.
     */
    public Matches getInlierMatches() {
        return convertPointMatchListToMatches(inliers);
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
               ", modelDataString: '" + model.toDataString() +
               "', inlierCount: " + getInlierPointMatchList().size() +
               ", inlierRatio: " + getInlierRatio() +
               '}';
    }

    /**
     * @param  pointMatchList  list of point matches to convert.
     *
     * @return the specified point match list in {@link Matches} form.
     */
    public static Matches convertPointMatchListToMatches(final List<PointMatch> pointMatchList) {

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
                    p[j][i] = local1[j];
                    q[j][i] = local2[j];
                }

                w[i] = pointMatch.getWeight();

            }

            matches = new Matches(p, q, w);

        } else {
            matches = new Matches(new double[1][0], new double[1][0], new double[0]);
        }


        return matches;
    }

}

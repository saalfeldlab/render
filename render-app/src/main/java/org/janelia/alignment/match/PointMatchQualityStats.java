package org.janelia.alignment.match;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;

import org.janelia.alignment.RenderParameters;

/**
 * Container for calculating and storing point match quality stats.
 * Though rendered canvases are often scaled, stats are converted to and recorded in full resolution pixels.
 *
 * @author Eric Trautman
 */
public class PointMatchQualityStats
        implements Serializable {

    private List<Double> consensusSetDeltaXStandardDeviations;
    private List<Double> consensusSetDeltaYStandardDeviations;
    private double[] aggregateDeltaXAndYStandardDeviation;
    private Long pImageArea;
    private Long qImageArea;
    private Double pConvexHullArea;
    private Double qConvexHullArea;

    PointMatchQualityStats() {
    }

    public void calculate(final RenderParameters pRenderParameters,
                          final RenderParameters qRenderParameters,
                          final List<List<PointMatch>> consensusSetInliers,
                          final Model aggregateModel)
            throws NotEnoughDataPointsException, IllDefinedDataPointsException, IllegalArgumentException {

        consensusSetDeltaXStandardDeviations = new ArrayList<>();
        consensusSetDeltaYStandardDeviations = new ArrayList<>();
        aggregateDeltaXAndYStandardDeviation = new double[] { 0.0, 0.0 };

        final double renderScale = pRenderParameters.getScale();
        if (renderScale != qRenderParameters.getScale()) {
            throw new IllegalArgumentException("p tile render scale " + renderScale +
                                               " differs from q tile render scale " + qRenderParameters.getScale());
        }

        // render parameters width and height are full scale
        pImageArea = (long) pRenderParameters.getWidth() * pRenderParameters.getHeight();
        qImageArea = (long) qRenderParameters.getWidth() * qRenderParameters.getHeight();

        pConvexHullArea = 0.0;
        qConvexHullArea = 0.0;

        final List<PointMatch> aggregatedInliers = new ArrayList<>();
        for (final List<PointMatch> consensusSet : consensusSetInliers) {
            if (consensusSet.size() > 0) {
                final double[] worldDeltaXAndYStandardDeviation = getWorldDeltaXAndYStandardDeviation(consensusSet);
                consensusSetDeltaXStandardDeviations.add(worldDeltaXAndYStandardDeviation[0] / renderScale);
                consensusSetDeltaYStandardDeviations.add(worldDeltaXAndYStandardDeviation[1] / renderScale);
                consensusSet.forEach(pm -> aggregatedInliers.add(new PointMatch(pm.getP1().clone(),
                                                                                pm.getP2().clone())));
            }
        }

        if (consensusSetDeltaXStandardDeviations.size() > 0) {

            if (aggregateModel == null) {
                aggregateDeltaXAndYStandardDeviation = new double[]{
                        consensusSetDeltaXStandardDeviations.get(0),
                        consensusSetDeltaYStandardDeviations.get(0)
                };
            } else {
                aggregateModel.fit(aggregatedInliers);
                aggregateDeltaXAndYStandardDeviation = getWorldDeltaXAndYStandardDeviation(aggregatedInliers);
            }

            final List<Point> pAggregatedPointList = new ArrayList<>(aggregatedInliers.size());
            PointMatch.sourcePoints(aggregatedInliers, pAggregatedPointList);
            pConvexHullArea = calculateConvexHullArea(pAggregatedPointList, renderScale);

            final List<Point> qAggregatedPointList = new ArrayList<>(aggregatedInliers.size());
            PointMatch.targetPoints(aggregatedInliers, qAggregatedPointList);
            qConvexHullArea = calculateConvexHullArea(qAggregatedPointList, renderScale);

        }
    }

    List<Double> getConsensusSetDeltaXStandardDeviations() {
        return consensusSetDeltaXStandardDeviations;
    }

    List<Double> getConsensusSetDeltaYStandardDeviations() {
        return consensusSetDeltaYStandardDeviations;
    }

    double[] getAggregateDeltaXAndYStandardDeviation() {
        return aggregateDeltaXAndYStandardDeviation;
    }

    Long getpImageArea() {
        return pImageArea;
    }

    Long getqImageArea() {
        return qImageArea;
    }

    Double getpConvexHullArea() {
        return pConvexHullArea;
    }

    Double getqConvexHullArea() {
        return qConvexHullArea;
    }

//    @JsonIgnore
//    public double getpConvexHullAreaPercentage() {
//        return pConvexHullArea / pImageArea;
//    }
//
//    @JsonIgnore
//    public double getqConvexHullAreaPercentage() {
//        return pConvexHullArea / pImageArea;
//    }

    private static double[] getWorldDeltaXAndYStandardDeviation(final List<PointMatch> pointMatchList) {
        final double[] deltaWorldX = new double[pointMatchList.size()];
        final double[] deltaWorldY = new double[pointMatchList.size()];
        for (int i = 0; i < pointMatchList.size(); i++) {
            final PointMatch pointMatch = pointMatchList.get(i);
            final Point p = pointMatch.getP1();
            final Point q = pointMatch.getP2();
            deltaWorldX[i] = p.getW()[0] - q.getW()[0];
            deltaWorldY[i] = p.getW()[1] - q.getW()[1];
        }
        return new double[] { calculateStandardDeviation(deltaWorldX), calculateStandardDeviation(deltaWorldY) };
    }

    private static double calculateStandardDeviation(final double[] values) {
        double sum = 0.0;
        double squaredDifferenceSum = 0.0;
        for (final double v : values) {
            sum += v;
        }
        final double mean = sum / values.length;
        for (final double v : values) {
            squaredDifferenceSum += Math.pow(v - mean, 2);
        }
        final double variance = squaredDifferenceSum / values.length;
        return Math.sqrt(variance);
    }

    private static double calculateConvexHullArea(final List<Point> aggregatedPointList,
                                                  final double renderScale) {
        final List<Point> convexHull = ConvexHull.deriveConvexHull(aggregatedPointList);
        final double pScaledConvexHullArea = ConvexHull.calculatePolygonArea(convexHull);
        return pScaledConvexHullArea / renderScale / renderScale;
    }


}

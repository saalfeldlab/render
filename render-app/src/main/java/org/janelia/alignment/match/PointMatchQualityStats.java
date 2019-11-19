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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private Long pImageWidth;
    private Long pImageHeight;
    private Double pConvexHullArea;
    private Double pCoverageWidth;
    private Double pCoverageHeight;

    private Long qImageWidth;
    private Long qImageHeight;
    private Double qConvexHullArea;
    private Double qCoverageWidth;
    private Double qCoverageHeight;

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
        pImageWidth = (long) pRenderParameters.getWidth();
        pImageHeight = (long) pRenderParameters.getHeight();

        qImageWidth = (long) qRenderParameters.getWidth();
        qImageHeight = (long) qRenderParameters.getHeight();

        pConvexHullArea = 0.0;
        qConvexHullArea = 0.0;

        final List<PointMatch> aggregatedInliers = new ArrayList<>();
        for (final List<PointMatch> consensusSet : consensusSetInliers) {
            if (consensusSet.size() > 0) {
                final double[] worldDeltaXAndYStandardDeviation = getWorldDeltaXAndYStandardDeviation(consensusSet);
                consensusSetDeltaXStandardDeviations.add(worldDeltaXAndYStandardDeviation[0] / renderScale);
                consensusSetDeltaYStandardDeviations.add(worldDeltaXAndYStandardDeviation[1] / renderScale);
                for (final PointMatch pm : consensusSet) {
                    aggregatedInliers.add(new PointMatch(pm.getP1().clone(), pm.getP2().clone()));
                }
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

            pCoverageWidth = calculateCoverageDistance(pAggregatedPointList, renderScale, 0);
            pCoverageHeight = calculateCoverageDistance(pAggregatedPointList, renderScale, 1);

            qCoverageWidth = calculateCoverageDistance(qAggregatedPointList, renderScale, 0);
            qCoverageHeight = calculateCoverageDistance(qAggregatedPointList, renderScale, 1);
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
        return pImageWidth * pImageHeight;
    }

    Long getqImageArea() {
        return qImageWidth * qImageHeight;
    }

    Double getpConvexHullArea() {
        return pConvexHullArea;
    }

    Double getqConvexHullArea() {
        return qConvexHullArea;
    }

    public boolean hasSufficientAreaCoverage(final double minPercentage) {
        final double pCoveragePercentage = pConvexHullArea / getpImageArea();
        final double qCoveragePercentage = qConvexHullArea / getqImageArea();
        return hasSufficientCoverage("area", pCoveragePercentage, qCoveragePercentage, minPercentage);
    }

    public boolean hasSufficientWidthCoverage(final double minPercentage) {
        final double pCoveragePercentage = pCoverageWidth / pImageWidth;
        final double qCoveragePercentage = qCoverageWidth / qImageWidth;
        return hasSufficientCoverage("width", pCoveragePercentage, qCoveragePercentage, minPercentage);
    }

    public boolean hasSufficientHeightCoverage(final double minPercentage) {
        final double pCoveragePercentage = pCoverageHeight / pImageHeight;
        final double qCoveragePercentage = qCoverageHeight / qImageHeight;
        return hasSufficientCoverage("height", pCoveragePercentage, qCoveragePercentage, minPercentage);
    }

    private static boolean hasSufficientCoverage(final String context,
                                                 final double pCoveragePercentage,
                                                 final double qCoveragePercentage,
                                                 final double minPercentage) {
        // TODO: confirm max is what we want here
        final double coveragePercentage = Math.max(pCoveragePercentage, qCoveragePercentage);
        final boolean result = (coveragePercentage >= minPercentage);
        LOG.debug("hasSufficientCoverage: returning {} for p {} coverage of {}% and q {} coverage of {}%",
                  result, context, pCoveragePercentage, context, qCoveragePercentage);
        return result;
    }

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

    private static double calculateCoverageDistance(final List<Point> aggregatedPointList,
                                                    final double renderScale,
                                                    final int dimension) {
        double distance = 0.0;
        if (aggregatedPointList.size() > 0) {
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (final Point p : aggregatedPointList) {
                final double value = p.getL()[dimension];
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
            distance = Math.abs(max - min) / renderScale;
        }
        return distance;
    }

    private static final Logger LOG = LoggerFactory.getLogger(PointMatchQualityStats.class);

}

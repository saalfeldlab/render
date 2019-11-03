package org.janelia.alignment.match;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;

/**
 * Container for calculating and storing point match quality stats.
 *
 * @author Eric Trautman
 */
public class PointMatchQualityStats
        implements Serializable {

    private List<Double> consensusSetDeltaXStandardDeviations;
    private List<Double> consensusSetDeltaYStandardDeviations;
    private double[] aggregateDeltaXAndYStandardDeviation;

    PointMatchQualityStats() {
    }

    public void calculate(final List<List<PointMatch>> consensusSetInliers,
                          final Model aggregateModel)
            throws NotEnoughDataPointsException, IllDefinedDataPointsException {

        consensusSetDeltaXStandardDeviations = new ArrayList<>();
        consensusSetDeltaYStandardDeviations = new ArrayList<>();
        aggregateDeltaXAndYStandardDeviation = new double[] { 0.0, 0.0 };

        final List<PointMatch> aggregatedInliers = new ArrayList<>();
        for (final List<PointMatch> consensusSet : consensusSetInliers) {
            if (consensusSet.size() > 0) {
                final double[] worldDeltaXAndYStandardDeviation = getWorldDeltaXAndYStandardDeviation(consensusSet);
                consensusSetDeltaXStandardDeviations.add(worldDeltaXAndYStandardDeviation[0]);
                consensusSetDeltaYStandardDeviations.add(worldDeltaXAndYStandardDeviation[1]);
                if (aggregateModel != null) {
                    consensusSet.forEach(pm -> aggregatedInliers.add(new PointMatch(pm.getP1().clone(),
                                                                                    pm.getP2().clone())));
                }
            }
        }

        if (aggregateModel == null) {
            if (consensusSetDeltaXStandardDeviations.size() > 0) {
                aggregateDeltaXAndYStandardDeviation = new double[]{
                        consensusSetDeltaXStandardDeviations.get(0),
                        consensusSetDeltaYStandardDeviations.get(0)
                };
            }
        } else {
            aggregateModel.fit(aggregatedInliers);
            this.aggregateDeltaXAndYStandardDeviation = getWorldDeltaXAndYStandardDeviation(aggregatedInliers);
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

}

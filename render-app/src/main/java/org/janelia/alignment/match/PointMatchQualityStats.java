package org.janelia.alignment.match;

import ij.process.ImageProcessor;

import java.io.Serializable;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

import mpicbg.models.AffineModel2D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;

import org.janelia.alignment.RenderParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.imglib2.util.Pair;

/**
 * Container for calculating and storing point match quality stats.
 * Stats are converted (as needed) and recorded in full scale pixels.
 *
 * @author Eric Trautman
 */
public class PointMatchQualityStats
        implements Serializable {

    private Integer totalNumberOfInliers;

    private List<Double> consensusSetDeltaXStandardDeviations;
    private List<Double> consensusSetDeltaYStandardDeviations;
    private double[] aggregateDeltaXAndYStandardDeviation;

    private Long overlappingCoveragePixels;
    private Long overlappingImagePixels;

    public PointMatchQualityStats() {
    }

    public void calculate(final RenderParameters pRenderParameters,
                          final ImageProcessor pMask,
                          final RenderParameters qRenderParameters,
                          final ImageProcessor qMask,
                          final List<List<PointMatch>> consensusSetInliers,
                          final Model aggregateModel,
                          final double overlapBlockRadius)
            throws NotEnoughDataPointsException, IllDefinedDataPointsException, IllegalArgumentException {

        final double renderScale = pRenderParameters.getScale();
        if (renderScale != qRenderParameters.getScale()) {
            throw new IllegalArgumentException("p tile render scale " + renderScale +
                                               " differs from q tile render scale " + qRenderParameters.getScale());
        }

        // NOTE: render parameters width and height are full scale
        calculate(renderScale,
                  pRenderParameters.getWidth(),
                  pRenderParameters.getHeight(),
                  pMask,
                  qRenderParameters.getWidth(),
                  qRenderParameters.getHeight(),
                  qMask,
                  consensusSetInliers,
                  aggregateModel,
                  overlapBlockRadius);
    }

    public void calculate(final double renderScale,
                          final long pFullScaleWidth,
                          final long pFullScaleHeight,
                          final ImageProcessor pMask,
                          final long qFullScaleWidth,
                          final long qFullScaleHeight,
                          final ImageProcessor qMask,
                          final List<List<PointMatch>> consensusSetInliers,
                          final Model aggregateModel,
                          final double fullScaleOverlapBlockRadius)
            throws NotEnoughDataPointsException, IllDefinedDataPointsException {

        consensusSetDeltaXStandardDeviations = new ArrayList<>();
        consensusSetDeltaYStandardDeviations = new ArrayList<>();
        aggregateDeltaXAndYStandardDeviation = new double[] { 0.0, 0.0 };

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

        totalNumberOfInliers = aggregatedInliers.size();

        if (totalNumberOfInliers > 0) {

            if (aggregateModel == null) {
                aggregateDeltaXAndYStandardDeviation = new double[]{
                        consensusSetDeltaXStandardDeviations.get(0),
                        consensusSetDeltaYStandardDeviations.get(0)
                };
            } else {
                aggregateModel.fit(aggregatedInliers);
                aggregateDeltaXAndYStandardDeviation = getWorldDeltaXAndYStandardDeviation(aggregatedInliers);
            }

            // use mask height and width if it exists to avoid rounding issues in coverage logic
            final int pWidth = (pMask == null) ? (int) Math.ceil(pFullScaleWidth * renderScale) : pMask.getWidth();
            final int pHeight = (pMask == null) ? (int) Math.ceil(pFullScaleHeight * renderScale) : pMask.getHeight();
            final int qWidth = (pMask == null) ? (int) Math.ceil(qFullScaleWidth * renderScale) : qMask.getWidth();
            final int qHeight = (pMask == null) ? (int) Math.ceil(qFullScaleHeight * renderScale) : qMask.getHeight();

            final AffineModel2D overlapModel = new AffineModel2D();
            final Pair<Long, Long> coverageStats =
                    CoverageUtils.computeOverlappingCoverage(pWidth, pHeight, pMask,
                                                             qWidth, qHeight, qMask,
                                                             aggregatedInliers,
                                                             overlapModel,
                                                             fullScaleOverlapBlockRadius * renderScale);

            overlappingCoveragePixels = (long) Math.ceil(coverageStats.getA() / renderScale);
            overlappingImagePixels = (long) Math.ceil(coverageStats.getB() / renderScale);
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

    Long getOverlappingImagePixels() {
        return overlappingImagePixels;
    }

    Long getOverlappingCoveragePixels() {
        return overlappingCoveragePixels;
    }

    public boolean hasSufficientQuantity(final Integer minNumberOfInliers) {
        return (minNumberOfInliers == null) || (totalNumberOfInliers >= minNumberOfInliers);
    }

    public boolean hasSufficientCoverage(final double minPercentage) {
        final double coveragePercentage =
                (overlappingCoveragePixels / (double) overlappingImagePixels) * 100.0;
        final boolean result = (coveragePercentage >= minPercentage);
        LOG.debug("hasSufficientCoverage: returning {} for coverage of {}%",
                  result, PERCENT_FORMATTER.format(coveragePercentage));
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

    private static final Logger LOG = LoggerFactory.getLogger(PointMatchQualityStats.class);

    private static final DecimalFormat PERCENT_FORMATTER = new DecimalFormat("0.00");

}

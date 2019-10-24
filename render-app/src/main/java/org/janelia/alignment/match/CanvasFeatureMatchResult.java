package org.janelia.alignment.match;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import mpicbg.models.Point;
import mpicbg.models.PointMatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encapsulates key data elements from canvas feature match derivation.
 *
 * @author Eric Trautman
 */
public class CanvasFeatureMatchResult implements Serializable {

    private final List<List<PointMatch>> consensusSetInliers;
    private final int totalNumberOfInliers;
    private final double inlierRatio;

    public CanvasFeatureMatchResult(final List<List<PointMatch>> consensusSetInliers,
                                    final int totalNumberOfCandidates) {

        this.consensusSetInliers = consensusSetInliers;
        int totalNumberOfInliers = 0;
        for (final List<PointMatch> setInliers : consensusSetInliers) {
            totalNumberOfInliers += setInliers.size();
        }
        this.totalNumberOfInliers = totalNumberOfInliers;
        if (totalNumberOfCandidates > 0) {
            this.inlierRatio = totalNumberOfInliers / (double) totalNumberOfCandidates;
        } else {
            this.inlierRatio = 0.0;
        }
    }

    private boolean foundMatches() {
        return totalNumberOfInliers > 0;
    }

    /**
     * @return collection of inlier matches.
     */
    public List<PointMatch> getInlierPointMatchList() {
        return consensusSetInliers.get(0);
    }

    List<Integer> getConsensusSetSizes() {
        final List<Integer> sizes = new ArrayList<>();
        if (consensusSetInliers != null) {
            //noinspection Convert2streamapi
            for (final List<PointMatch> consensusSet : consensusSetInliers) {
                sizes.add(consensusSet.size());
            }
        }
        return sizes;
    }

    /**
     * @param  renderScale  scale of rendered canvases (needed to return matches in full scale coordinates).
     * @param  pOffsets     full scale x[0] and y[1] offset for all pCanvas matches.
     * @param  qOffsets     full scale x[0] and y[1] offset for all qCanvas matches.
     *
     * @return collection of inlier matches.
     */
    List<CanvasMatches> getInlierMatchesList(final String pGroupId,
                                             final String pId,
                                             final String qGroupId,
                                             final String qId,
                                             final Double renderScale,
                                             final double[] pOffsets,
                                             final double[] qOffsets) {

        final List<CanvasMatches> list = new ArrayList<>();

        if (consensusSetInliers.size() == 1) {

            final List<PointMatch> inlierList = getInlierPointMatchList();

            if (inlierList.size() > 0) {
                final Matches inlierMatches =
                        convertPointMatchListToMatches(inlierList, renderScale, pOffsets, qOffsets);
                list.add(new CanvasMatches(pGroupId, pId, qGroupId, qId, inlierMatches));
            }

        } else {

            int consensusSetIndex = 0;
            for (final List<PointMatch> consensusSet : consensusSetInliers) {
                final Matches matches = convertPointMatchListToMatches(consensusSet, renderScale, pOffsets, qOffsets);
                final CanvasMatches canvasMatches = new CanvasMatches(pGroupId, pId, qGroupId, qId, matches);
                canvasMatches.setConsensusSetIndex(consensusSetIndex);
                list.add(canvasMatches);
                consensusSetIndex++;
            }

        }

        return list;
    }

    public void addInlierMatchesToList(final String pGroupId,
                                       final String pId,
                                       final String qGroupId,
                                       final String qId,
                                       final Double renderScale,
                                       final double[] pOffsets,
                                       final double[] qOffsets,
                                       final Double pairMaxDeltaStandardDeviation,
                                       final List<CanvasMatches> targetList) {

        if (foundMatches()) {

            final List<CanvasMatches> inlierList = getInlierMatchesList(pGroupId,
                                                                        pId,
                                                                        qGroupId,
                                                                        qId,
                                                                        renderScale,
                                                                        pOffsets,
                                                                        qOffsets);
            if (pairMaxDeltaStandardDeviation != null) {

                final List<List<Double>> worldDeltaStandardDeviations = getWorldDeltaStandardDeviations();
                final List<Double> deltaXStandardDeviations = worldDeltaStandardDeviations.get(0);
                final List<Double> deltaYStandardDeviations = worldDeltaStandardDeviations.get(1);

                for (int i = 0; i < inlierList.size(); i++) {

                    final CanvasMatches canvasMatches = inlierList.get(i);
                    final double dxStd = deltaXStandardDeviations.get(i);
                    final double dyStd = deltaYStandardDeviations.get(i);

                    if (dxStd > pairMaxDeltaStandardDeviation) {

                        LOG.warn("tossing matches between {} and {} because delta X standard deviation of {} is greater than {}",
                                 canvasMatches.getpId(), canvasMatches.getqId(), dxStd, pairMaxDeltaStandardDeviation);

                    } else if (dyStd > pairMaxDeltaStandardDeviation) {

                        LOG.warn("tossing matches between {} and {} because delta Y standard deviation of {} is greater than {}",
                                 canvasMatches.getpId(), canvasMatches.getqId(), dyStd, pairMaxDeltaStandardDeviation);

                    } else {
                        targetList.add(canvasMatches);
                    }

                }

            } else {
                targetList.addAll(inlierList);
            }
        }

    }

    @Override
    public String toString() {
        return "{'consensusSetSizes' : " + getConsensusSetSizes() + ", 'inlierRatio' : " + inlierRatio + '}';
    }

    List<List<Double>> getWorldDeltaStandardDeviations() {
        final List<Double> deltaXStandardDeviations = new ArrayList<>();
        final List<Double> deltaYStandardDeviations = new ArrayList<>();
        for (final List<PointMatch> consensusSet : consensusSetInliers) {
            final double[] worldDeltaXAndYStandardDeviation = getWorldDeltaXAndYStandardDeviation(consensusSet);
            deltaXStandardDeviations.add(worldDeltaXAndYStandardDeviation[0]);
            deltaYStandardDeviations.add(worldDeltaXAndYStandardDeviation[1]);
        }
        return Stream.of(deltaXStandardDeviations, deltaYStandardDeviations).collect(Collectors.toList());
    }

    public static double[] getWorldDeltaXAndYStandardDeviation(final List<PointMatch> pointMatchList) {
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

    /**
     * @param  pointMatchList  list of point matches to convert.
     * @param  renderScale     scale of rendered canvases (needed to return matches in full scale coordinates).
     *
     * @return the specified point match list in {@link Matches} form.
     */
    public static Matches convertPointMatchListToMatches(final List<PointMatch> pointMatchList,
                                                         final double renderScale) {
        return convertPointMatchListToMatches(pointMatchList, renderScale, CanvasId.ZERO_OFFSETS, CanvasId.ZERO_OFFSETS);
    }

    /**
     * @param  pointMatchList  list of point matches to convert.
     * @param  renderScale     scale of rendered canvases (needed to return matches in full scale coordinates).
     * @param  pOffsets        full scale x[0] and y[1] offset for all pCanvas matches.
     * @param  qOffsets        full scale x[0] and y[1] offset for all qCanvas matches.
     *
     * @return the specified point match list in {@link Matches} form.
     */
    private static Matches convertPointMatchListToMatches(final List<PointMatch> pointMatchList,
                                                          final double renderScale,
                                                          final double[] pOffsets,
                                                          final double[] qOffsets) {

        final Matches matches;

        final int pointMatchCount = pointMatchList.size();

        if (pointMatchCount > 0) {

            PointMatch pointMatch = pointMatchList.get(0);
            Point p1 = pointMatch.getP1();
            double[] local1 = p1.getL();
            final int dimensionCount = local1.length;

            final double[][] p = new double[dimensionCount][pointMatchCount];
            final double[][] q = new double[dimensionCount][pointMatchCount];
            final double[] w = new double[pointMatchCount];

            Point p2;
            double[] local2;
            for (int i = 0; i < pointMatchCount; i++) {

                pointMatch = pointMatchList.get(i);

                p1 = pointMatch.getP1();
                local1 = p1.getL();

                p2 = pointMatch.getP2();
                local2 = p2.getL();

                for (int j = 0; j < dimensionCount; j++) {
                    // point matches must be stored in full scale world coordinates
                    if (renderScale == 1.0) {
                        p[j][i] = local1[j] + pOffsets[j];
                        q[j][i] = local2[j] + qOffsets[j];
                    } else {
                        p[j][i] = (local1[j] / renderScale) + pOffsets[j];
                        q[j][i] = (local2[j] / renderScale) + qOffsets[j];
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

        final double[] w = matches.getWs();

        final int pointMatchCount = w.length;
        final List<PointMatch> pointMatchList = new ArrayList<>(pointMatchCount);

        if (pointMatchCount > 0) {
            final double[][] p = matches.getPs();
            final double[][] q = matches.getQs();

            final int dimensionCount = p.length;

            for (int matchIndex = 0; matchIndex < pointMatchCount; matchIndex++) {

                final double[] pLocal = new double[dimensionCount];
                final double[] qLocal = new double[dimensionCount];

                for (int dimensionIndex = 0; dimensionIndex < dimensionCount; dimensionIndex++) {
                    pLocal[dimensionIndex] = p[dimensionIndex][matchIndex];
                    qLocal[dimensionIndex] = q[dimensionIndex][matchIndex];
                }

                pointMatchList.add(new PointMatch(new Point(pLocal), new Point(qLocal), w[matchIndex]));
            }
        }

        return pointMatchList;
    }

    private static final Logger LOG = LoggerFactory.getLogger(CanvasFeatureMatchResult.class);
}

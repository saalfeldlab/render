package org.janelia.alignment.match;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import mpicbg.models.Model;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;

import static org.janelia.alignment.match.CanvasFeatureMatcher.FilterType.AGGREGATED_CONSENSUS_SETS;

/**
 * Encapsulates key data elements from canvas feature match derivation.
 *
 * @author Eric Trautman
 */
public class CanvasFeatureMatchResult implements Serializable {

    private final CanvasFeatureMatcher matcher;
    private final List<List<PointMatch>> consensusSetInliers;
    private final int totalNumberOfInliers;
    private final double inlierRatio;

    public CanvasFeatureMatchResult(final CanvasFeatureMatcher matcher,
                                    final List<List<PointMatch>> consensusSetInliers,
                                    final int totalNumberOfCandidates) {

        this.matcher = matcher;
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
                                       final List<CanvasMatches> targetList) {

        if (foundMatches()) {

            final List<CanvasMatches> inlierList = getInlierMatchesList(pGroupId,
                                                                        pId,
                                                                        qGroupId,
                                                                        qId,
                                                                        renderScale,
                                                                        pOffsets,
                                                                        qOffsets);
            targetList.addAll(inlierList);

        }

    }

    PointMatchQualityStats calculateQualityStats()
            throws IllegalArgumentException {

        final PointMatchQualityStats qualityStats = new PointMatchQualityStats();

        final Model aggregateModel;
        if (AGGREGATED_CONSENSUS_SETS.equals(matcher.getFilterType()) || (consensusSetInliers.size() > 1)) {
            aggregateModel = matcher.getModel();
        } else {
            aggregateModel = null;
        }

        try {
            qualityStats.calculate(consensusSetInliers, aggregateModel);
        } catch (final Exception e) {
            throw new IllegalArgumentException("failed to fit aggregate model for point match quality calculation", e);
        }

        return qualityStats;
    }

    @Override
    public String toString() {
        return "{'consensusSetSizes' : " + getConsensusSetSizes() + ", 'inlierRatio' : " + inlierRatio + '}';
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

}

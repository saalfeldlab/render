package org.janelia.alignment.util;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;

import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.annotations.ApiModelProperty;

/**
 * Utility to calculate residual distances for aligned tiles.
 *
 * @author Eric Trautman
 */
public class ResidualCalculator implements Serializable {

    public static class InputData implements Serializable {

        private final String pTileId;
        private final String qTileId;
        private final StackId matchRenderStackId;
        private final MatchCollectionId matchCollectionId;
        private final Boolean includeDetails;

        // empty ctor required for JSON
        @SuppressWarnings("unused")
        public InputData() {
            this(null, null, null, null, null);
        }

        InputData(final String pTileId,
                  final String qTileId,
                  final StackId matchRenderStackId,
                  final MatchCollectionId matchCollectionId,
                  final Boolean includeDetails) {
            this.matchCollectionId = matchCollectionId;
            this.matchRenderStackId = matchRenderStackId;
            this.pTileId = pTileId;
            this.qTileId = qTileId;
            this.includeDetails = includeDetails;
        }

        @ApiModelProperty(name = "pTileId")
        public String getPTileId() {
            return pTileId;
        }

        @ApiModelProperty(name = "qTileId")
        public String getQTileId() {
            return qTileId;
        }

        public MatchCollectionId getMatchCollectionId() {
            return matchCollectionId;
        }

        public StackId getMatchRenderStackId() {
            return matchRenderStackId;
        }

        Boolean getIncludeDetails() {
            return includeDetails;
        }

        public InputData copy(final String pTileId,
                              final String qTileId) {
            return new InputData(pTileId,
                                 qTileId,
                                 this.matchRenderStackId,
                                 this.matchCollectionId,
                                 this.includeDetails);
        }
    }

    public static class Result implements Serializable {

        private final StackId alignedStackId;
        private final InputData inputData;
        private final Double medianDistance;
        private final Double meanDistance;
        private final Double maxDistance;
        private final Double rootMeanSquareError;
        private final List<Double> distanceList;

        // empty ctor required for JSON
        @SuppressWarnings("unused")
        public Result() {
            this(null, null);
        }

        public Result(final StackId alignedStackId,
                      final InputData inputData) {
            this(alignedStackId, inputData, null, null, null, null, null);
        }

        public Result(final StackId alignedStackId,
                      final InputData inputData,
                      final Double medianDistance,
                      final Double meanDistance,
                      final Double maxDistance,
                      final Double rootMeanSquareError,
                      final List<Double> distanceList) {

            this.alignedStackId = alignedStackId;
            this.inputData = inputData;
            this.medianDistance = medianDistance;
            this.meanDistance = meanDistance;
            this.maxDistance = maxDistance;
            this.rootMeanSquareError = rootMeanSquareError;
            if (inputData.getIncludeDetails()) {
                this.distanceList = distanceList;
            } else {
                this.distanceList = null;
            }
        }

        public Double getRootMeanSquareError() {
            return rootMeanSquareError;
        }

        @Override
        public String toString() {
            final String totals = String.format("%25s, %40s, %6.2f, %6.2f, %6.2f, %6.2f",
                                                alignedStackId.getProject(), alignedStackId.getStack(),
                                                medianDistance, meanDistance, maxDistance, rootMeanSquareError);
            final StringBuilder sb = new StringBuilder(totals);
            if (inputData.getIncludeDetails() && (distanceList != null)) {
                for (final Double distance : distanceList) {
                    sb.append(String.format(", %6.2f", distance));
                }
            }
            sb.append('\n');
            return sb.toString();
        }

        @JsonIgnore
        public static String getHeader() {
            return String.format("%25s, %40s, %6s, %6s, %6s, %6s, details\n", "project", "stack", "median", "mean", "max", "RMSE");
        }

    }

    public Result run(final StackId alignedStackId,
                      final InputData inputData,
                      final List<PointMatch> localMatchList,
                      final TileSpec pAlignedTileSpec,
                      final TileSpec qAlignedTileSpec)
            throws NoninvertibleModelException {

        final List<Double> distanceList = new ArrayList<>(localMatchList.size());

        for (final PointMatch localMatch : localMatchList) {
            final double[] pLocal = localMatch.getP1().getL();
            final double[] pWorld = pAlignedTileSpec.getWorldCoordinates(pLocal[0], pLocal[1]);
            final double[] qLocal = localMatch.getP2().getL();
            final double[] qWorld = qAlignedTileSpec.getWorldCoordinates(qLocal[0], qLocal[1]);

            final double xDeltaSquared = Math.pow(pWorld[0] - qWorld[0], 2);
            final double yDeltaSquared = Math.pow(pWorld[1] - qWorld[1], 2);
            final double distance = Math.sqrt(xDeltaSquared + yDeltaSquared);

            distanceList.add(distance);
        }

        final Result result;
        if (distanceList.size() > 0) {

            Collections.sort(distanceList);

            final double max = distanceList.get(distanceList.size() - 1);

            final int middleIndex = distanceList.size() / 2;
            double median = distanceList.get(middleIndex);
            if (distanceList.size() % 2 == 0) {
                median = (median + distanceList.get(middleIndex - 1)) / 2.0;
            }

            double distanceSum = 0;
            double distanceSquaredSum = 0;
            for (final double distance : distanceList) {
                distanceSum += distance;
                distanceSquaredSum += Math.pow(distance, 2);
            }

            final double arithmeticMean = (distanceSum / distanceList.size());
            final double rootMeanSquareError = Math.sqrt(distanceSquaredSum / distanceList.size());

            result = new Result(alignedStackId, inputData, median, arithmeticMean, max, rootMeanSquareError, distanceList);

        } else {

            result = new Result(alignedStackId, inputData);

        }

        LOG.info("run: exit, rmse is {}, distanceList.size is {} for pid {} and qId {}",
                 result.rootMeanSquareError, distanceList.size(), inputData.pTileId, inputData.qTileId);

        return result;
    }

    public static List<PointMatch> convertMatchesToLocal(final List<PointMatch> worldMatchList,
                                                   final TileSpec pMatchTileSpec,
                                                   final TileSpec qMatchTileSpec) {

        final List<PointMatch> localMatchList = new ArrayList<>(worldMatchList.size());
        Point pPoint;
        Point qPoint;
        for (final PointMatch worldMatch : worldMatchList) {
            try {
                pPoint = getLocalPoint(worldMatch.getP1(), pMatchTileSpec);
                qPoint = getLocalPoint(worldMatch.getP2(), qMatchTileSpec);
                localMatchList.add(new PointMatch(pPoint, qPoint));
            } catch (final NoninvertibleModelException e) {
                LOG.warn("skipping match", e);
            }
        }
        return localMatchList;
    }

    private static Point getLocalPoint(final Point worldPoint,
                                       final TileSpec tileSpec)
            throws NoninvertibleModelException {
        final double[] world = worldPoint.getL();
        final double[] local = tileSpec.getLocalCoordinates(world[0], world[1], tileSpec.getMeshCellSize());
        return new Point(local);
    }

    private static final Logger LOG = LoggerFactory.getLogger(ResidualCalculator.class);
}

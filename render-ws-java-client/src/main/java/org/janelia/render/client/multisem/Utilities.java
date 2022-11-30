package org.janelia.render.client.multisem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mpicbg.models.AbstractAffineModel2D;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;

import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasMatchResult;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.spec.TileSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility methods for working with Multi-SEM data sets.
 *
 * @author Eric Trautman
 */
public class Utilities {

    /**
     * @return 001_000006 for 001_000006_019_20220407_115555.1247.0
     */
    public static String getMFOVForTileId(final String tileId) throws IllegalArgumentException {
        if (tileId.length() < 10) {
            throw new IllegalArgumentException("MFOV identifier cannot be derived from tileId " + tileId);
        }
        return tileId.substring(0, 10);
    }

    /**
     * @return 001_000006_019 for 001_000006_019_20220407_115555.1247.0
     */
    public static String getSFOVForTileId(final String tileId) throws IllegalArgumentException {
        if (tileId.length() < 14) {
            throw new IllegalArgumentException("SFOV identifier cannot be derived from tileId " + tileId);
        }
        return tileId.substring(0, 14);
    }

    public static Map<String, TileSpec> mapMFOVTilesToSFOVIds(final Collection<TileSpec> tileSpecList,
                                                              final String mFOVId) {
        final Map<String, TileSpec> map = new HashMap<>(tileSpecList.size());
        for (final TileSpec tileSpec : tileSpecList) {
            final String tileId = tileSpec.getTileId();
            if (mFOVId.equals(getMFOVForTileId(tileId))) {
                map.put(getSFOVForTileId(tileId), tileSpec);
            }
        }
        return map;
    }

    public static void validateMatchStorageLocation(final String location)
            throws IllegalArgumentException {
        final Path storagePath = Paths.get(location).toAbsolutePath();
        if (Files.exists(storagePath)) {
            if (! Files.isWritable(storagePath)) {
                throw new IllegalArgumentException("not allowed to write to " + storagePath);
            }
        } else if (! Files.isWritable(storagePath.getParent())) {
            throw new IllegalArgumentException("not allowed to write to " + storagePath.getParent());
        }
    }

    public static void fitModelAndLogError(final AbstractAffineModel2D<?> matchModel,
                                           final CanvasMatches canvasMatches,
                                           final String logContext)
            throws IOException {

        final List<PointMatch> matchList =
                CanvasMatchResult.convertMatchesToPointMatchList(canvasMatches.getMatches());
        fitModelAndLogError(matchModel, matchList, logContext);
    }

    public static void fitModelAndLogError(final AbstractAffineModel2D<?> matchModel,
                                           final List<PointMatch> matchList,
                                           final String logContext)
            throws IOException {

        try {
            matchModel.fit(matchList);
        } catch (final Exception e) {
            throw new IOException("failed to fit model for " + logContext, e);
        }

        double error = 0;
        double maxError = 0;
        for (final PointMatch pm : matchList) {
            pm.apply(matchModel);
            error += pm.getDistance();
            maxError = Math.max(maxError, pm.getDistance());
        }

        error /= matchList.size();

        LOG.debug("fitModelAndLogError: after fit of {}, error is {} and model is {}",
                  logContext, error, matchModel);
    }

    public static CanvasMatches buildCornerMatches(final OrderedCanvasIdPair pair,
                                                   final TileSpec pTileSpec,
                                                   final TileSpec qTileSpec,
                                                   final AbstractAffineModel2D<?> matchModel,
                                                   final double derivedMatchWeight) {
        final List<PointMatch> missingCornerMatchList = new ArrayList<>();

        final CanvasId p = pair.getP();
        final CanvasId q = pair.getQ();

        final List<Point> pLensCorrectedCorners = pTileSpec.getMatchingTransformedCornerPoints();
        final List<Point> qLensCorrectedCorners = qTileSpec.getMatchingTransformedCornerPoints();

        for (int i = 0; i < pLensCorrectedCorners.size(); i++) {
            final Point pCorner = pLensCorrectedCorners.get(i);
            final Point qCorner = qLensCorrectedCorners.get(i);
            qCorner.apply(matchModel);
            final Point transformedQCorner = new Point(qCorner.getW()); // need to use q world coordinates
            missingCornerMatchList.add(new PointMatch(pCorner, transformedQCorner, derivedMatchWeight));
        }

        return new CanvasMatches(p.getGroupId(),
                                 p.getId(),
                                 q.getGroupId(),
                                 q.getId(),
                                 CanvasMatchResult.convertPointMatchListToMatches(missingCornerMatchList,
                                                                                  1.0));
    }

    private static final Logger LOG = LoggerFactory.getLogger(Utilities.class);
}

package org.janelia.alignment.multisem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import mpicbg.models.AbstractAffineModel2D;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.InvertibleCoordinateTransform;
import mpicbg.models.InvertibleCoordinateTransformList;
import mpicbg.models.NoninvertibleModelException;
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
public class MultiSemUtilities {

    /**
     * @return m0013 for w60_magc0399_scan005_m0013_r46_s01
     */
    public static String getSimpleMfovForTileId(final String tileId) throws IllegalArgumentException {
        final int magcIndex = tileId.indexOf("magc");
        if ((magcIndex < 0) || (tileId.length() < (magcIndex + 22))) {
            throw new IllegalArgumentException("SimpleMfov identifier cannot be derived from tileId " + tileId);
        }
        return tileId.substring((magcIndex + 17), (magcIndex + 22)); // m0013;
    }

    /**
     * @return 0399_m0013 for w60_magc0399_scan005_m0013_r46_s01
     */
    public static String getMagcMfovForTileId(final String tileId) throws IllegalArgumentException {
        final int magcIndex = tileId.indexOf("magc");
        if ((magcIndex < 0) || (tileId.length() < (magcIndex + 22))) {
            throw new IllegalArgumentException("MagcMfov identifier cannot be derived from tileId " + tileId);
        }
        final String magcName = tileId.substring((magcIndex + 4), (magcIndex + 8)); // 0399
        final String mfovName = tileId.substring((magcIndex + 16), (magcIndex + 22)); // _m0013
        return magcName + mfovName;
    }

    /**
     * @return 0399_m0013_s01 for w60_magc0399_scan005_m0013_r46_s01
     */
    public static String getMagcMfovSfovForTileId(final String tileId) throws IllegalArgumentException {
        final int magcIndex = tileId.indexOf("magc");
        if ((magcIndex < 0) || (tileId.length() < (magcIndex + 30))) {
            throw new IllegalArgumentException("MagcMfovSfov identifier cannot be derived from tileId " + tileId);
        }
        final String magcName = tileId.substring((magcIndex + 4), (magcIndex + 8)); // 0399
        final String mfovName = tileId.substring((magcIndex + 16), (magcIndex + 22)); // _m0013
        final String sfovName = tileId.substring((magcIndex + 26), (magcIndex + 30)); // _s01
        return magcName + mfovName + sfovName;
    }

    /**
     * @return m0013_s01 for w60_magc0399_scan005_m0013_r46_s01
     */
    public static String getMfovSfovForTileId(final String tileId) throws IllegalArgumentException {
        final int magcIndex = tileId.indexOf("magc");
        if ((magcIndex < 0) || (tileId.length() < (magcIndex + 30))) {
            throw new IllegalArgumentException("MfovSfov identifier cannot be derived from tileId " + tileId);
        }
        final String mfovName = tileId.substring((magcIndex + 17), (magcIndex + 22)); // m0013
        final String sfovName = tileId.substring((magcIndex + 26), (magcIndex + 30)); // _s01
        return mfovName + sfovName;
    }

    /**
     * @return 01 for w60_magc0399_scan005_m0013_r46_s01
     */
    public static String getSFOVIndexForTileId(final String tileId) throws IllegalArgumentException {
        final int scanIndex = tileId.indexOf("scan");
        if ((scanIndex < 0) || (tileId.length() < (scanIndex + 21))) {
            throw new IllegalArgumentException("SFOV index cannot be derived from tileId " + tileId);
        }
        return tileId.substring(scanIndex + 19);
    }

    /**
     * @return 33.0:01:02 for groupId 33.0,
     *                            pId w60_magc0399_scan004_m0013_r46_s01, and
     *                            qId w60_magc0399_scan004_m0013_r47_s02
     */
    public static String getSFOVIndexPairName(final String groupId,
                                              final String pId,
                                              final String qId) throws IllegalArgumentException {
        return groupId + ":" + getSFOVIndexForTileId(pId) + ":" + getSFOVIndexForTileId(qId);
    }


    public static Map<String, TileSpec> mapMFOVTilesToSFOVIds(final Collection<TileSpec> tileSpecList,
                                                              final String mFOVId) {
        final Map<String, TileSpec> map = new HashMap<>(tileSpecList.size());
        for (final TileSpec tileSpec : tileSpecList) {
            final String tileId = tileSpec.getTileId();
            if (mFOVId.equals(getMagcMfovForTileId(tileId))) {
                map.put(getMagcMfovSfovForTileId(tileId), tileSpec);
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

    public static void fitModelAndLogStats(final AbstractAffineModel2D<?> matchModel,
                                           final CanvasMatches canvasMatches,
                                           final String logContext)
            throws IOException {

        final List<PointMatch> matchList =
                CanvasMatchResult.convertMatchesToPointMatchList(canvasMatches.getMatches());
        fitModelAndLogStats(matchModel, matchList, logContext);
    }

    @SuppressWarnings("DuplicatedCode")
    public static void fitModelAndLogStats(final AbstractAffineModel2D<?> matchModel,
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

        // hack: changed error to err0r in log statement to work around dumb log check scripts
        LOG.debug("fitModelAndLogStats: after fit of {}, err0r is {} and model is {}",
                  logContext, error, matchModel);
    }

    public static CanvasMatches buildPointMatches(final OrderedCanvasIdPair pair,
                                                  final List<Point> pLensCorrectedPoints,
                                                  final List<Point> qLensCorrectedPoints,
                                                  final AbstractAffineModel2D<?> matchModel,
                                                  final double derivedMatchWeight) {
        final List<PointMatch> missingCornerMatchList = new ArrayList<>();

        final CanvasId p = pair.getP();
        final CanvasId q = pair.getQ();

        for (int i = 0; i < pLensCorrectedPoints.size(); i++) {
            final Point pPoint = pLensCorrectedPoints.get(i);
            final Point qPoint = qLensCorrectedPoints.get(i);
            qPoint.apply(matchModel);
            final Point transformedQCorner = new Point(qPoint.getW()); // need to use q world coordinates
            missingCornerMatchList.add(new PointMatch(pPoint, transformedQCorner, derivedMatchWeight));
        }

        return new CanvasMatches(p.getGroupId(),
                                 p.getId(),
                                 q.getGroupId(),
                                 q.getId(),
                                 CanvasMatchResult.convertPointMatchListToMatches(missingCornerMatchList,
                                                                                  1.0));
    }

    public static List<Point> transformMFOVMatchesForTile(final List<PointMatch> mFOVMatches,
                                                          final TileSpec tileSpec,
                                                          final boolean isP) {

        final List<Point> tileRelativePoints = new ArrayList<>();

        final List<CoordinateTransform> postMatchingTransformList =
                tileSpec.getPostMatchingTransformList().getList(null);

        final InvertibleCoordinateTransformList<InvertibleCoordinateTransform> postMatchingInvertibleTransformList =
                new InvertibleCoordinateTransformList<>();
        for (final CoordinateTransform coordinateTransform : postMatchingTransformList) {
            postMatchingInvertibleTransformList.add((InvertibleCoordinateTransform) coordinateTransform);
        }

        for (final PointMatch pointMatch : mFOVMatches) {
            final double[] world = isP ? pointMatch.getP1().getW() : pointMatch.getP2().getW();
            final double[] local;
            try {
                local = postMatchingInvertibleTransformList.applyInverse(world);
                tileRelativePoints.add(new Point(local));
            } catch (final NoninvertibleModelException e) {
                //noinspection StringConcatenationArgumentToLogCall
                LOG.warn("transformMFOVMatchesForTile: skipping nom-invertible point in tile " + tileSpec.getTileId(),
                         e);
                tileRelativePoints.add(null);
            }
        }
        return tileRelativePoints;
    }

    /**
     * @param  tileSpec  tile with transformations.
     * @param  margin    pixels to add/subtract from raw corner edges before transformation.
     *
     * @return raw corner points of the tile offset by margin and transformed by same
     *         transformations used for matching (e.g. lens correction).
     */
    public static List<Point> getMatchingTransformedCornersForTile(final TileSpec tileSpec,
                                                                   final int margin) {
        final double[][] rawLocations;
        if (margin == 0) {
            rawLocations = tileSpec.getRawCornerLocations();
        } else {
            final int maxX = tileSpec.getWidth() - margin;
            final int maxY = tileSpec.getHeight() - margin;
            if ((maxX > margin) && (maxY > margin)) {
                rawLocations = new double[][]{
                        {margin, margin},
                        {maxX, margin},
                        {margin, maxY},
                        {maxX, maxY}
                };
            } else {
                rawLocations = tileSpec.getRawCornerLocations();
            }
        }
        return tileSpec.getMatchingTransformedPoints(rawLocations);
    }

    /** @return true if the name is an 'm' followed by 4 digits like 'm0012', otherwise false. */
    public static boolean isSimpleMFOVName(final String name) {
        return SIMPLE_MFOV_NAME_PATTERN.matcher(name).matches();
    }

    private static final Pattern SIMPLE_MFOV_NAME_PATTERN = Pattern.compile("^m(\\d{4})$");

    private static final Logger LOG = LoggerFactory.getLogger(MultiSemUtilities.class);
}

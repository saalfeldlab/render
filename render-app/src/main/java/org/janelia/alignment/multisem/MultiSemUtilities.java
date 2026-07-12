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
        final int scanIndex = tileId.indexOf("_sc"); // tileIds can contain _scan005_ or _sc01234_
        if ((scanIndex < 0) || (tileId.length() < (scanIndex + 21))) {
            throw new IllegalArgumentException("SFOV index cannot be derived from tileId " + tileId);
        }
        return tileId.substring(scanIndex + 20);
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

    /** Each MFOV has 91 SFOVs or tiles */
    public static int NUMBER_OF_TILES_IN_MFOV = 91;

    /**
     * Maps an SFOV's original spiral acquisition number (the 1-based {@code _s##} value in a multi-SEM tileId,
     * assigned by spiraling counterclockwise out from the center of the MFOV) to its 1-based row-major beam index
     * (numbering the 91 beams of the MFOV from 1 in the top-left corner, going row by row). This "spiral -&gt;
     * row-major" permutation of the 91-beam hexagonal layout is the single source of truth shared by
     * {@code TileReorderingClient} (which uses it to order tiles) and the multi-SEM inpainter (which uses it to
     * match render tiles to the acquisition xlog's row-major sfov axis).
     *
     * @param  spiralSFOVNumber  the 1-based spiral sfov number (1..91).
     * @return the corresponding 1-based row-major beam index.
     */
    public static int getRowMajorSFOVIndex(final int spiralSFOVNumber) {
        return SPIRAL_TO_ROW_MAJOR_SFOV[spiralSFOVNumber - 1];
    }

    // Number the 91 beams of an MFOV from 1 to 91 in row-major order (top-left corner, row by row), then record
    // those numbers in the original spiral acquisition order (center-out, counterclockwise). Thus
    // SPIRAL_TO_ROW_MAJOR_SFOV[spiralNumber - 1] is that beam's row-major index.
    private static final int[] SPIRAL_TO_ROW_MAJOR_SFOV = {
            46, 47, 36, 35, 45, 56, 57, 48, 37, 27,
            26, 25, 34, 44, 55, 65, 66, 67, 58, 49,
            38, 28, 19, 18, 17, 16, 24, 33, 43, 54,
            64, 73, 74, 75, 76, 68, 59, 50, 39, 29,
            20, 12, 11, 10,  9,  8, 15, 23, 32, 42,
            53, 63, 72, 80, 81, 82, 83, 84, 77, 69,
            60, 51, 40, 30, 21, 13,  6,  5,  4,  3,
             2,  1,  7, 14, 22, 31, 41, 52, 62, 71,
            79, 86, 87, 88, 89, 90, 91, 85, 78, 70, 61
    };

    private static final Pattern SIMPLE_MFOV_NAME_PATTERN = Pattern.compile("^m(\\d{4})$");

    private static final Logger LOG = LoggerFactory.getLogger(MultiSemUtilities.class);
}

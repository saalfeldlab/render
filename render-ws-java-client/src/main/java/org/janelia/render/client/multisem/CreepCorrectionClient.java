package org.janelia.render.client.multisem;

import static org.janelia.alignment.spec.ResolvedTileSpecCollection.TransformApplicationMethod.INSERT_BEFORE_LAST;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.janelia.alignment.match.CanvasMatchResult;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.Matches;
import org.janelia.alignment.multisem.MultiSemUtilities;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.transform.StageCreepCorrectionTransform;
import org.janelia.render.client.RenderDataClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mpicbg.models.AffineModel2D;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;

/**
 * Estimates and applies piezo creep correction for multi-SEM sFOV tiles within each mFOV.
 *
 * <p>The correction is estimated by fitting pairwise affine transforms between geometrically
 * adjacent sFOV pairs and extracting the y-stretch factor. A double-exponential correction
 * (using {@link org.janelia.alignment.transform.SEMDistortionTransformA}) is then derived
 * and inserted into each tile's transform list.</p>
 *
 * @author Michael Innerberger
 */
public class CreepCorrectionClient {

    // physical constants for the double-exponential model
    private static final double TAU_0 = 0.42;      // seconds
    private static final double TAU_1 = 4.0;       // seconds
    private static final double PIXEL_DWELL = 800e-9; // seconds
    private static final double LINE_WIDTH = 2000;  // pixels
    private static final double L0 = TAU_0 / (PIXEL_DWELL * LINE_WIDTH);  // 262500 lines
    private static final double L1 = TAU_1 / (PIXEL_DWELL * LINE_WIDTH);  // 2500000 lines

    // line numbers used for stretch-to-amplitude conversion
    private static final int TOP_LINE = 50;
    private static final int BOTTOM_LINE = 1700;

    // validation thresholds
    private static final double MIN_STRETCH = 0.9;
    private static final double MAX_STRETCH = 1.1;
    private static final int MIN_VALID_STRETCHES = 10;
    private static final double MAX_STRETCH_STDDEV = 0.02;

    // RANSAC parameters for pairwise affine estimation
    private static final int RANSAC_ITERATIONS = 1000;
    private static final double RANSAC_MAX_EPSILON = 10.0;
    private static final double RANSAC_MIN_INLIER_RATIO = 0.1;
    private static final int RANSAC_MIN_NUM_INLIERS = 7;
    private static final double RANSAC_MAX_TRUST = 3.0;

    // minimum offset for geometric neighbor detection (in stage coordinate units)
    private static final double MIN_NEIGHBOR_OFFSET = 100.0;

    /**
     * Processes all mFOVs for a given z-layer: loads tiles and matches, estimates creep correction,
     * applies it where valid, and saves the results to the target stack.
     *
     * @return per-mFOV results (one per mFOV, including skipped ones)
     */
    public List<MfovResult> processZLayer(final double z,
                                          final RenderDataClient renderDataClient,
                                          final RenderDataClient matchDataClient,
                                          final String stack,
                                          final String targetStack)
            throws IOException {

        LOG.info("processZLayer: entry, z={}", z);

        final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(stack, z);

        // group tiles by mFOV
        final Map<String, List<TileSpec>> mfovToTiles = new HashMap<>();
        for (final TileSpec tileSpec : resolvedTiles.getTileSpecs()) {
            final String mfov = MultiSemUtilities.getMagcMfovForTileId(tileSpec.getTileId());
            mfovToTiles.computeIfAbsent(mfov, k -> new ArrayList<>()).add(tileSpec);
        }

        // load all within-group matches and index by tile pair
        final String groupId = String.valueOf(z);
        final List<CanvasMatches> allMatches = matchDataClient.getMatchesWithinGroup(groupId, false);
        final Map<String, CanvasMatches> pairKeyToMatches = new HashMap<>();
        for (final CanvasMatches match : allMatches) {
            final String pMFOV = MultiSemUtilities.getMagcMfovForTileId(match.getpId());
            final String qMFOV = MultiSemUtilities.getMagcMfovForTileId(match.getqId());
            if (pMFOV.equals(qMFOV)) {
                pairKeyToMatches.put(pairKey(match.getpId(), match.getqId()), match);
            }
        }

        // process each mFOV independently
        final List<MfovResult> mfovResults = new ArrayList<>();
        int correctedMFOVCount = 0;
        int skippedMFOVCount = 0;
        for (final Map.Entry<String, List<TileSpec>> entry : mfovToTiles.entrySet()) {
            final String mfov = entry.getKey();
            final List<TileSpec> mfovTiles = entry.getValue();

            final MfovResult result = processMFOV(mfov, mfovTiles, pairKeyToMatches, resolvedTiles);
            mfovResults.add(result);
            LOG.info("processMFOV: {}", result.toCsvRow());

            if (result.isValid) {
                correctedMFOVCount++;
            } else {
                skippedMFOVCount++;
            }
        }

        // save all tiles (both corrected and uncorrected)
        renderDataClient.saveResolvedTiles(resolvedTiles, targetStack, z);

        LOG.info("processZLayer: exit, z={}, correctedMFOVs={}, skippedMFOVs={}, totalTiles={}",
                 z, correctedMFOVCount, skippedMFOVCount, resolvedTiles.getTileCount());

        return mfovResults;
    }

    /**
     * Processes a single mFOV: finds neighbor pairs, estimates stretch, validates, and applies correction.
     */
    MfovResult processMFOV(final String mfov,
                            final List<TileSpec> mfovTiles,
                            final Map<String, CanvasMatches> pairKeyToMatches,
                            final ResolvedTileSpecCollection resolvedTiles) {

        // find geometric neighbor pairs
        final List<TilePair> neighborPairs = findGeometricNeighborPairs(mfovTiles);

        if (neighborPairs.isEmpty()) {
            return MfovResult.invalid(mfov, 0, "no geometric neighbor pairs found");
        }

        // estimate y-stretch for each pair that has matches
        final List<Double> stretches = new ArrayList<>();
        int pairsWithoutMatches = 0;
        for (final TilePair pair : neighborPairs) {
            final String key = pairKey(pair.pTileId, pair.qTileId);
            final CanvasMatches matches = pairKeyToMatches.get(key);

            if (matches == null) {
                pairsWithoutMatches++;
                continue;
            }

            final double yStretch = estimateYStretchForPair(
                    CanvasMatchResult.convertMatchesToPointMatchList(matches.getMatches()));
            stretches.add(yStretch);
        }

        if (pairsWithoutMatches > 0) {
            LOG.info("processMFOV: {} of {} neighbor pairs in mFOV {} had no matches",
                     pairsWithoutMatches, neighborPairs.size(), mfov);
        }

        // validate and build result
        final MfovResult result = validateStretches(stretches, mfov);

        if (result.isValid) {
            final Set<String> mfovTileIds = mfovTiles.stream()
                    .map(TileSpec::getTileId)
                    .collect(Collectors.toSet());
            applyCorrectionToMFOV(resolvedTiles, mfovTileIds, result.correctionSpec);
        }

        return result;
    }

    /**
     * Finds geometric neighbor pairs (lower-right and lower-left) using stage positions,
     * replicating the Python prototype's neighbor-finding logic.
     */
    List<TilePair> findGeometricNeighborPairs(final List<TileSpec> mfovTiles) {
        final List<TilePair> pairs = new ArrayList<>();

        for (final TileSpec targetTile : mfovTiles) {
            final StageCoordinates target = getStageCoordinates(targetTile);
            if (target == null) continue;

            // find closest lower-right neighbor
            findClosestNeighbor(targetTile.getTileId(), target, mfovTiles, true)
                    .ifPresent(pairs::add);

            // find closest lower-left neighbor
            findClosestNeighbor(targetTile.getTileId(), target, mfovTiles, false)
                    .ifPresent(pairs::add);
        }

        return pairs;
    }

    private static StageCoordinates getStageCoordinates(final TileSpec targetTile) {
        final LayoutData targetLayout = targetTile.getLayout();
        if (targetLayout == null || targetLayout.getStageX() == null || targetLayout.getStageY() == null) {
            return null;
        }
        final double targetX = targetLayout.getStageX();
        final double targetY = targetLayout.getStageY();
        return new StageCoordinates(targetX, targetY);
    }

    private Optional<TilePair> findClosestNeighbor(final String targetTileId,
                                                   final StageCoordinates target,
                                                   final List<TileSpec> candidates,
                                                   final boolean lowerRight) {
        double minDist = Double.MAX_VALUE;
        String closestId = null;

        for (final TileSpec candidate : candidates) {
            if (candidate.getTileId().equals(targetTileId)) {
                continue;
            }
            final LayoutData layout = candidate.getLayout();
            final StageCoordinates stage = getStageCoordinates(candidate);

            if (layout == null || stage == null) {
                continue;
            }

            final boolean xCondition = lowerRight
                    ? (stage.x > target.x + MIN_NEIGHBOR_OFFSET)
                    : (stage.x < target.x - MIN_NEIGHBOR_OFFSET);

            if (xCondition && (stage.y > target.y + MIN_NEIGHBOR_OFFSET)) {
                final double dist = Math.sqrt(Math.pow(stage.x - target.x, 2) + Math.pow(stage.y - target.y, 2));
                if (dist < minDist) {
                    minDist = dist;
                    closestId = candidate.getTileId();
                }
            }
        }

        if (closestId != null) {
            // normalize pair ordering so pairKey lookups work regardless of match storage order
            final String pId = targetTileId.compareTo(closestId) < 0 ? targetTileId : closestId;
            final String qId = targetTileId.compareTo(closestId) < 0 ? closestId : targetTileId;
            return java.util.Optional.of(new TilePair(pId, qId));
        }
        return java.util.Optional.empty();
    }

    /**
     * Estimates the y-stretch factor from a set of point matches by fitting an affine model with RANSAC.
     *
     * @return the y-stretch (m11 element of the affine), or {@link Double#NaN} on failure
     */
    double estimateYStretchForPair(final List<PointMatch> candidates) {
        if (candidates.size() < RANSAC_MIN_NUM_INLIERS) {
            return Double.NaN;
        }

        final AffineModel2D model = new AffineModel2D();
        final List<PointMatch> inliers = new ArrayList<>();

        try {
            model.filterRansac(candidates,
                               inliers,
                               RANSAC_ITERATIONS,
                               RANSAC_MAX_EPSILON,
                               RANSAC_MIN_INLIER_RATIO,
                               RANSAC_MIN_NUM_INLIERS,
                               RANSAC_MAX_TRUST);
        } catch (final NotEnoughDataPointsException e) {
            return Double.NaN;
        }

        if (inliers.isEmpty()) {
            return Double.NaN;
        }

        final double[] affineData = new double[6];
        model.toArray(affineData);
        // affineData layout: [m00, m10, m01, m11, m02, m12]
        return affineData[3]; // m11 = y-stretch
    }

    /**
     * Computes the creep correction amplitude from the median y-stretch.
     * Returns a negative value (same sign as the Python prototype's {@code calculate_a_helper_func}),
     * which is used directly as the coefficient in {@link StageCreepCorrectionTransform}'s backward map:
     * {@code y_source = y_world + a * exp(-y_world / L)}.
     */
    static double computeCorrectionAmplitude(final double medianStretch) {
        final double b = (-1.0 / L0) * Math.exp(-(double) TOP_LINE / L0)
                       + (-1.0 / L1) * Math.exp(-(double) TOP_LINE / L1);
        final double c = (-1.0 / L0) * Math.exp(-(double) BOTTOM_LINE / L0)
                       + (-1.0 / L1) * Math.exp(-(double) BOTTOM_LINE / L1);
        return (medianStretch - 1.0) / (b - c * medianStretch);
    }

    /**
     * Validates stretch estimates for a single mFOV and returns the result.
     */
    MfovResult validateStretches(final List<Double> stretches, final String mfov) {
        final int totalPairs = stretches.size();

        if (totalPairs == 0) {
            return MfovResult.invalid(mfov, 0, "no stretch estimates available");
        }

        // filter out NaN and out-of-range values
        final List<Double> validStretches = new ArrayList<>();
        int nanCount = 0;
        int outOfRangeCount = 0;
        for (final double s : stretches) {
            if (Double.isNaN(s)) {
                nanCount++;
            } else if (s < MIN_STRETCH || s > MAX_STRETCH) {
                outOfRangeCount++;
            } else {
                validStretches.add(s);
            }
        }

        if (nanCount > totalPairs / 2) {
            LOG.warn("validateStretches: mFOV {} has {} NaN stretches out of {} total",
                     mfov, nanCount, totalPairs);
        }
        if (outOfRangeCount > 0) {
            LOG.info("validateStretches: mFOV {} had {} stretches outside [{}, {}]",
                     mfov, outOfRangeCount, MIN_STRETCH, MAX_STRETCH);
        }

        if (validStretches.size() < MIN_VALID_STRETCHES) {
            return MfovResult.invalid(mfov, totalPairs,
                    "only " + validStretches.size() + " valid stretches (need " + MIN_VALID_STRETCHES +
                    ") nanCount=" + nanCount + " outOfRange=" + outOfRangeCount);
        }

        // compute median
        validStretches.sort(Double::compareTo);
        final double medianStretch;
        final int n = validStretches.size();
        if (n % 2 == 0) {
            medianStretch = (validStretches.get(n / 2 - 1) + validStretches.get(n / 2)) / 2.0;
        } else {
            medianStretch = validStretches.get(n / 2);
        }

        // compute standard deviation
        final double mean = validStretches.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        final double variance = validStretches.stream()
                .mapToDouble(s -> Math.pow(s - mean, 2))
                .sum() / validStretches.size();
        final double stddev = Math.sqrt(variance);

        if (stddev > MAX_STRETCH_STDDEV) {
            return new MfovResult(mfov, medianStretch, stddev, Double.NaN,
                                  validStretches.size(), totalPairs, null,
                                  "stretch stddev exceeds threshold " + MAX_STRETCH_STDDEV);
        }

        // compute amplitude
        final double amplitude = computeCorrectionAmplitude(medianStretch);
        if (!Double.isFinite(amplitude)) {
            return new MfovResult(mfov, medianStretch, stddev, amplitude,
                                  validStretches.size(), totalPairs, null,
                                  "computed amplitude is not finite");
        }

        return new MfovResult(mfov, medianStretch, stddev, amplitude,
                              validStretches.size(), totalPairs,
                              buildCorrectionTransformSpec(amplitude), "OK");
    }

    /**
     * Builds a {@link LeafTransformSpec} for the creep correction using {@code StageCreepCorrectionTransform}.
     * The amplitude is negative (same as the Python prototype), defining the backward map
     * {@code y_s = y_w + a*exp(-y_w/L0) + a*exp(-y_w/L1)} where negative {@code a} reads from above,
     * compressing the top of the image to correct for creep stretch.
     */
    static TransformSpec buildCorrectionTransformSpec(final double amplitude) {
        final String dataString = amplitude + "," + L0 + "," + amplitude + "," + L1 + ",1";
        return new LeafTransformSpec("org.janelia.alignment.transform.StageCreepCorrectionTransform", dataString);
    }

    /**
     * Applies the correction transform to all tiles of an mFOV by inserting it before the last
     * (alignment) transform, placing it after the existing scan correction.
     */
    private void applyCorrectionToMFOV(final ResolvedTileSpecCollection tiles,
                                       final Set<String> mfovTileIds,
                                       final TransformSpec correctionSpec) {
        for (final String tileId : mfovTileIds) {
            tiles.addTransformSpecToTile(tileId, correctionSpec, INSERT_BEFORE_LAST);
        }
    }

    /**
     * Creates a canonical pair key for match lookups. CanvasMatches normalizes p < q,
     * so we ensure the same ordering.
     */
    private static String pairKey(final String id1, final String id2) {
        return id1.compareTo(id2) < 0 ? id1 + "::" + id2 : id2 + "::" + id1;
    }

    /**
     * Transforms all matches for a given group (z-layer) using the collected creep corrections.
     * Handles both within-group and outside-group matches.
     */
    public void transformMatchesForGroup(final String groupId,
                                         final Map<String, List<MfovResult>> allResults,
                                         final RenderDataClient sourceMatchClient,
                                         final RenderDataClient targetMatchClient)
            throws IOException {

        LOG.info("transformMatchesForGroup: entry, groupId={}", groupId);

        // get within-group and outside-group matches
        final List<CanvasMatches> withinMatches = sourceMatchClient.getMatchesWithinGroup(groupId, false);
        final List<CanvasMatches> outsideMatches = sourceMatchClient.getMatchesOutsideGroup(groupId, false);

        final List<CanvasMatches> allMatches = new ArrayList<>(withinMatches);
        allMatches.addAll(outsideMatches);

        if (allMatches.isEmpty()) {
            LOG.info("transformMatchesForGroup: no matches found for groupId={}", groupId);
            return;
        }

        // transform and collect matches
        final List<CanvasMatches> transformedMatches = new ArrayList<>();
        for (final CanvasMatches cm : allMatches) {
            transformedMatches.add(transformCanvasMatches(cm, allResults));
        }

        // save to target match collection
        targetMatchClient.saveMatches(transformedMatches);

        LOG.info("transformMatchesForGroup: exit, groupId={}, transformed {} matches",
                 groupId, transformedMatches.size());
    }

    /**
     * Transforms a single CanvasMatches by applying creep corrections to both p and q coordinates.
     */
    static CanvasMatches transformCanvasMatches(final CanvasMatches cm,
                                                final Map<String, List<MfovResult>> allResults) {
        final Matches matches = cm.getMatches();
        final double[][] ps = matches.getPs();
        final double[][] qs = matches.getQs();
        final double[] ws = matches.getWs();
        final int n = ws.length;

        // deep copy coordinate arrays
        final double[][] newPs = new double[][] { Arrays.copyOf(ps[0], n), Arrays.copyOf(ps[1], n) };
        final double[][] newQs = new double[][] { Arrays.copyOf(qs[0], n), Arrays.copyOf(qs[1], n) };

        // transform p and q coordinates using their respective tile's creep correction
        transformMatchCoordinates(newPs, cm.getpId(), cm.getpGroupId(), allResults);
        transformMatchCoordinates(newQs, cm.getqId(), cm.getqGroupId(), allResults);

        return new CanvasMatches(cm.getpGroupId(), cm.getpId(),
                                 cm.getqGroupId(), cm.getqId(),
                                 new Matches(newPs, newQs, Arrays.copyOf(ws, n)));
    }

    /**
     * Transforms match coordinates in place by applying creep correction.
     * Match coordinates are in post-lens/pre-alignment space (montage matches are derived before
     * alignment), which is the same space where the CC transform operates (it is inserted
     * before the alignment transform). So we apply CC directly without touching alignment.
     */
    static void transformMatchCoordinates(final double[][] coords,
                                          final String tileId,
                                          final String groupId,
                                          final Map<String, List<MfovResult>> allResults) {

        // look up CC spec for this tile's mFOV
        final String mfov = MultiSemUtilities.getMagcMfovForTileId(tileId);
        final List<MfovResult> layerResults = allResults.get(groupId);
        if (layerResults == null) {
            return;
        }

        TransformSpec ccSpec = null;
        for (final MfovResult r : layerResults) {
            if (r.mfov.equals(mfov) && r.correctionSpec != null) {
                ccSpec = r.correctionSpec;
                break;
            }
        }
        if (ccSpec == null) {
            return;
        }

        final CoordinateTransform ccTransform = ccSpec.getNewInstance();
        for (int i = 0; i < coords[0].length; i++) {
            final double[] point = new double[] { coords[0][i], coords[1][i] };
            ccTransform.applyInPlace(point);
            coords[0][i] = point[0];
            coords[1][i] = point[1];
        }
    }

    /** Result of processing a single mFOV, including validation outcome and correction spec. */
    public static class MfovResult implements Serializable {

        public static final String CSV_HEADER = "mfov,medianStretch,stddev,amplitude,validPairs,totalPairs,isValid,diagnosticMessage";

        public final String mfov;
        public final double medianStretch;
        public final double stddev;
        public final double amplitude;
        public final int validPairs;
        public final int totalPairs;
        public final boolean isValid;
        public final String diagnosticMessage;
        public final TransformSpec correctionSpec;

        MfovResult(final String mfov,
                   final double medianStretch,
                   final double stddev,
                   final double amplitude,
                   final int validPairs,
                   final int totalPairs,
                   final TransformSpec correctionSpec,
                   final String diagnosticMessage) {
            this.mfov = mfov;
            this.medianStretch = medianStretch;
            this.stddev = stddev;
            this.amplitude = amplitude;
            this.validPairs = validPairs;
            this.totalPairs = totalPairs;
            this.isValid = correctionSpec != null;
            this.diagnosticMessage = diagnosticMessage;
            this.correctionSpec = correctionSpec;
        }

        static MfovResult invalid(final String mfov, final int totalPairs, final String reason) {
            return new MfovResult(mfov, Double.NaN, Double.NaN, Double.NaN, 0, totalPairs, null, reason);
        }

        public String toCsvRow() {
            return mfov + "," + medianStretch + "," + stddev + "," + amplitude + ","
                   + validPairs + "," + totalPairs + "," + isValid + "," + diagnosticMessage;
        }
    }

    /** A pair of tile IDs representing geometrically adjacent sFOVs. */
    static class TilePair {
        final String pTileId;
        final String qTileId;

        TilePair(final String pTileId, final String qTileId) {
            this.pTileId = pTileId;
            this.qTileId = qTileId;
        }
    }

    static class StageCoordinates {
        final double x;
        final double y;

        StageCoordinates(final double x, final double y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(CreepCorrectionClient.class);
}

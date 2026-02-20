package org.janelia.render.client.multisem;

import static org.janelia.alignment.spec.ResolvedTileSpecCollection.TransformApplicationMethod.INSERT_BEFORE_LAST;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.janelia.alignment.match.CanvasMatchResult;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.multisem.MultiSemUtilities;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.TransformSpecMetaData;
import org.janelia.render.client.RenderDataClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mpicbg.models.AffineModel2D;
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
     * @return number of tiles processed
     */
    public int processZLayer(final double z,
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
        int correctedMFOVCount = 0;
        int skippedMFOVCount = 0;
        for (final Map.Entry<String, List<TileSpec>> entry : mfovToTiles.entrySet()) {
            final String mfov = entry.getKey();
            final List<TileSpec> mfovTiles = entry.getValue();

            final boolean corrected = processMFOV(mfov, mfovTiles, pairKeyToMatches, resolvedTiles);
            if (corrected) {
                correctedMFOVCount++;
            } else {
                skippedMFOVCount++;
            }
        }

        // save all tiles (both corrected and uncorrected)
        renderDataClient.saveResolvedTiles(resolvedTiles, targetStack, z);

        LOG.info("processZLayer: exit, z={}, correctedMFOVs={}, skippedMFOVs={}, totalTiles={}",
                 z, correctedMFOVCount, skippedMFOVCount, resolvedTiles.getTileCount());

        return resolvedTiles.getTileCount();
    }

    /**
     * Processes a single mFOV: finds neighbor pairs, estimates stretch, validates, and applies correction.
     *
     * @return true if correction was applied, false if skipped
     */
    boolean processMFOV(final String mfov,
                        final List<TileSpec> mfovTiles,
                        final Map<String, CanvasMatches> pairKeyToMatches,
                        final ResolvedTileSpecCollection resolvedTiles) {

        // find geometric neighbor pairs
        final List<TilePair> neighborPairs = findGeometricNeighborPairs(mfovTiles);

        if (neighborPairs.isEmpty()) {
            LOG.warn("processMFOV: no geometric neighbor pairs found for mFOV {}, skipping", mfov);
            return false;
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

        // validate results
        final ValidationResult validation = validateResults(stretches, mfov);

        if (!validation.isValid) {
            LOG.warn("processMFOV: skipping mFOV {} - {}", mfov, validation.diagnosticMessage);
            return false;
        }

        // build and apply correction transform
        final TransformSpec correctionSpec = buildCorrectionTransformSpec(validation.amplitude);
        final Set<String> mfovTileIds = mfovTiles.stream()
                .map(TileSpec::getTileId)
                .collect(Collectors.toSet());
        applyCorrectionToMFOV(resolvedTiles, mfovTileIds, correctionSpec);

        LOG.info("processMFOV: applied creep correction to mFOV {} with amplitude={}, medianStretch={}, stddev={}",
                 mfov, validation.amplitude, validation.medianStretch, validation.stddev);

        return true;
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
     * Centralized validation of all stretch estimates for a single mFOV.
     * On failure, returns an invalid result; the mFOV should be skipped (uploaded without correction).
     */
    ValidationResult validateResults(final List<Double> stretches, final String mfov) {
        final int totalPairs = stretches.size();

        if (totalPairs == 0) {
            return ValidationResult.invalid("no stretch estimates available");
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
            LOG.warn("validateResults: mFOV {} has {} NaN stretches out of {} total",
                     mfov, nanCount, totalPairs);
        }
        if (outOfRangeCount > 0) {
            LOG.info("validateResults: mFOV {} had {} stretches outside [{}, {}]",
                     mfov, outOfRangeCount, MIN_STRETCH, MAX_STRETCH);
        }

        if (validStretches.size() < MIN_VALID_STRETCHES) {
            return ValidationResult.invalid(
                    "only " + validStretches.size() + " valid stretches (need " + MIN_VALID_STRETCHES +
                    "), nanCount=" + nanCount + ", outOfRange=" + outOfRangeCount);
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
            return ValidationResult.invalid(
                    "stretch stddev " + stddev + " exceeds threshold " + MAX_STRETCH_STDDEV +
                    " (median=" + medianStretch + ", validCount=" + validStretches.size() + ")");
        }

        // compute amplitude
        final double amplitude = computeCorrectionAmplitude(medianStretch);
        if (!Double.isFinite(amplitude)) {
            return ValidationResult.invalid(
                    "computed amplitude is not finite (median=" + medianStretch + ")");
        }

        LOG.info("validateResults: mFOV {} - totalPairs={}, valid={}, nan={}, outOfRange={}, " +
                 "median={}, stddev={}, amplitude={}",
                 mfov, totalPairs, validStretches.size(), nanCount, outOfRangeCount,
                 medianStretch, stddev, amplitude);

        return new ValidationResult(true, medianStretch, stddev, amplitude, "OK");
    }

    /**
     * Builds a {@link LeafTransformSpec} for the creep correction using {@code StageCreepCorrectionTransform}.
     * The amplitude is negative (same as the Python prototype), defining the backward map
     * {@code y_s = y_w + a*exp(-y_w/L0) + a*exp(-y_w/L1)} where negative {@code a} reads from above,
     * compressing the top of the image to correct for creep stretch.
     */
    static TransformSpec buildCorrectionTransformSpec(final double amplitude) {
        final String dataString = amplitude + "," + L0 + "," + amplitude + "," + L1 + ",1";
        final LeafTransformSpec spec = new LeafTransformSpec(
                "org.janelia.alignment.transform.StageCreepCorrectionTransform", dataString);
        spec.addLabel(TransformSpecMetaData.INCLUDE_LABEL);
        return spec;
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

    /** A pair of tile IDs representing geometrically adjacent sFOVs. */
    static class TilePair {
        final String pTileId;
        final String qTileId;

        TilePair(final String pTileId, final String qTileId) {
            this.pTileId = pTileId;
            this.qTileId = qTileId;
        }
    }

    /** Result of validating stretch estimates for an mFOV. */
    static class ValidationResult {
        final boolean isValid;
        final double medianStretch;
        final double stddev;
        final double amplitude;
        final String diagnosticMessage;

        ValidationResult(final boolean isValid,
                         final double medianStretch,
                         final double stddev,
                         final double amplitude,
                         final String diagnosticMessage) {
            this.isValid = isValid;
            this.medianStretch = medianStretch;
            this.stddev = stddev;
            this.amplitude = amplitude;
            this.diagnosticMessage = diagnosticMessage;
        }

        static ValidationResult invalid(final String diagnosticMessage) {
            return new ValidationResult(false, Double.NaN, Double.NaN, Double.NaN, diagnosticMessage);
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

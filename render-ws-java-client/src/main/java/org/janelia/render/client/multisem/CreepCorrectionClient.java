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
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
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
    private static final double L0 = TAU_0 / (PIXEL_DWELL * LINE_WIDTH);  // 262.5 lines
    private static final double L1 = TAU_1 / (PIXEL_DWELL * LINE_WIDTH);  // 2500 lines

    // line numbers used for stretch-to-amplitude conversion
    private static final int TOP_LINE = 50;
    private static final int BOTTOM_LINE = 1700;

    // validation thresholds
    private static final double MIN_STRETCH = 0.9;
    private static final double MAX_STRETCH = 1.1;
    private static final int MIN_VALID_STRETCHES = 10;
    private static final double MAX_STRETCH_STDDEV = 0.02;
    private static final double MIN_MEDIAN_STRETCH = 0.995;

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

        final String stackAndZ = stack + "::z" + z;
        LOG.info("processZLayer: entry, {}", stackAndZ);

        final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(stack, z);

        // group tiles by mFOV
        final Map<String, List<TileSpec>> mfovToTiles = new HashMap<>();
        for (final TileSpec tileSpec : resolvedTiles.getTileSpecs()) {
            final String mfov = MultiSemUtilities.getMagcMfovForTileId(tileSpec.getTileId());
            mfovToTiles.computeIfAbsent(mfov, k -> new ArrayList<>()).add(tileSpec);
        }

        // load all within-group matches and index by tile pair
        // (match groups are section ids which can differ from z, e.g. after a stack is collapsed)
        final Set<String> groupIds = resolvedTiles.getTileSpecs().stream()
                .map(TileSpec::getSectionId)
                .collect(Collectors.toSet());

        LOG.info("processZLayer: {} has {} groupIds {}",
                 stackAndZ, groupIds.size(), groupIds);

        final Map<String, CanvasMatches> pairKeyToMatches = new HashMap<>();
        for (final String groupId : groupIds) {
            for (final CanvasMatches match : matchDataClient.getMatchesWithinGroup(groupId, false)) {
                final String pMFOV = MultiSemUtilities.getMagcMfovForTileId(match.getpId());
                final String qMFOV = MultiSemUtilities.getMagcMfovForTileId(match.getqId());
                if (pMFOV.equals(qMFOV)) {
                    pairKeyToMatches.put(pairKey(match.getpId(), match.getqId()), match);
                }
            }
        }

        // process each mFOV independently
        final List<MfovResult> mfovResults = new ArrayList<>();
        int correctedMFOVCount = 0;
        int skippedMFOVCount = 0;
        for (final Map.Entry<String, List<TileSpec>> entry : mfovToTiles.entrySet()) {
            final String mfov = entry.getKey();
            final List<TileSpec> mfovTiles = entry.getValue();

            LOG.info("processZLayer: {}, processing mFOV {}", stackAndZ, mfov);
            final MfovResult result = processMFOV(mfov, mfovTiles, pairKeyToMatches, resolvedTiles);
            mfovResults.add(result);

            if (result.isValid) {
                correctedMFOVCount++;
                LOG.info("processZLayer: {}, successfully corrected mFOV {}", stackAndZ, mfov);
            } else {
                skippedMFOVCount++;
                LOG.info("processZLayer: {}, failed correcting mFOV {}: {}",
                         stackAndZ, mfov, result.diagnosticMessage);
            }
        }

        // save all tiles (both corrected and uncorrected)
        renderDataClient.saveResolvedTiles(resolvedTiles, targetStack, z);

        LOG.info("processZLayer: exit, stack={}, z={}, correctedMFOVs={}, skippedMFOVs={}, totalTiles={}",
                 stack, z, correctedMFOVCount, skippedMFOVCount, resolvedTiles.getTileCount());

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
            final String key = pairKey(pair.upperTileId, pair.lowerTileId);
            final CanvasMatches matches = pairKeyToMatches.get(key);

            if (matches == null) {
                pairsWithoutMatches++;
                continue;
            }

            // matches are stored in tile ID order, but the stretch must be estimated from the upper to the lower tile
            List<PointMatch> pointMatches = CanvasMatchResult.convertMatchesToPointMatchList(matches.getMatches());
            if (! matches.getpId().equals(pair.upperTileId)) {
                pointMatches = new ArrayList<>(PointMatch.flip(pointMatches));
            }

            final double yStretch = estimateYStretchForPair(pointMatches);
            stretches.add(yStretch);
        }

        if (pairsWithoutMatches > 0) {
            LOG.info("processMFOV: {} of {} neighbor pairs in mFOV {} had no matches",
                     pairsWithoutMatches, neighborPairs.size(), mfov);
        }

        // validate and build result
        MfovResult result = validateStretches(stretches, mfov);

        if (result.isValid) {
            final Set<String> mfovTileIds = mfovTiles.stream()
                    .map(TileSpec::getTileId)
                    .collect(Collectors.toSet());

            // match coordinates are relative to the min corner of each tile's normalized bounds,
            // so capture that corner before the correction moves it for use when the matches are transformed
            final Map<String, double[]> tileIdToMatchFrameOrigin = new HashMap<>();
            for (final String tileId : mfovTileIds) {
                tileIdToMatchFrameOrigin.put(tileId, getNormalizedMin(resolvedTiles.getTileSpec(tileId)));
            }

            applyCorrectionToMFOV(resolvedTiles, mfovTileIds, result.correctionSpec);

            result = result.withMatchFrameOrigin(tileIdToMatchFrameOrigin);
        }

        return result;
    }

    /**
     * Uses the same normalization as the render web service's normalizeForMatching handling to find the
     * min corner of the tile's bounds in the space where its point match coordinates are defined.
     *
     * @return the [minX, minY] of the specified tile's bounds after normalizing it for matching.
     */
    static double[] getNormalizedMin(final TileSpec tileSpec) {
        final TileSpec normalizedTileSpec = tileSpec.slowClone();
        normalizedTileSpec.normalizeForMatching();
        return new double[] { normalizedTileSpec.getMinX(), normalizedTileSpec.getMinY() };
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
            return java.util.Optional.of(new TilePair(targetTileId, closestId));
        }
        return java.util.Optional.empty();
    }

    /**
     * Estimates the y-stretch factor from a set of point matches by fitting an affine model with RANSAC.
     * <br/><br/>
     * A least-squares fit treats the source points as exact, so noise in the source points
     * biases the fitted y-stretch toward 0.  The bias is large when the matches span only a small
     * range in y, as in a tile overlap.  The bias of the inverse fit is in the opposite direction,
     * so the geometric mean of the forward y-stretch and the inverse of the backward y-stretch cancels it
     * to first order.  Both fits use the same RANSAC inliers.
     *
     * @param  candidates  point matches from the upper tile (p) to the lower tile (q).
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

        // build the flipped matches from local coordinates, because RANSAC has set the world
        // coordinates of the p points to their transformed positions and fit uses them for targets
        final List<PointMatch> flippedInliers = new ArrayList<>(inliers.size());
        for (final PointMatch pm : inliers) {
            flippedInliers.add(new PointMatch(new Point(pm.getP2().getL().clone()),
                                              new Point(pm.getP1().getL().clone()),
                                              pm.getWeight()));
        }

        final AffineModel2D inverseModel = new AffineModel2D();
        try {
            inverseModel.fit(flippedInliers);
        } catch (final NotEnoughDataPointsException | IllDefinedDataPointsException e) {
            return Double.NaN;
        }

        // affineData layout: [m00, m10, m01, m11, m02, m12], m11 = y-stretch
        final double[] affineData = new double[6];
        final double[] inverseAffineData = new double[6];
        model.toArray(affineData);
        inverseModel.toArray(inverseAffineData);
        return Math.sqrt(affineData[3] / inverseAffineData[3]);
    }

    /**
     * Computes the creep correction amplitude from the median y-stretch.
     * Returns a positive value for stretches below 1 (e.g. about 3 for a stretch of 0.99),
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

        if (medianStretch > MIN_MEDIAN_STRETCH) {
            return new MfovResult(mfov, medianStretch, stddev, Double.NaN,
                                  validStretches.size(), totalPairs, null,
                                  "median stretch " + medianStretch + " above threshold " + MIN_MEDIAN_STRETCH);
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
     * The amplitude defines the backward map {@code y_s = y_w + a*exp(-y_w/L0) + a*exp(-y_w/L1)},
     * where positive {@code a} stretches the top of the image to correct for creep compression.
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
     * Transforms all matches with the specified pGroupId using the collected creep corrections.
     * Handles both within-group and outside-group matches.
     * <br/><br/>
     * Outside-group matches are only transformed when their pGroupId is the specified group.
     * The outside-group query also returns matches where the specified group is the qGroupId,
     * but those are transformed when their own pGroupId is processed.
     * This ensures that each match is transformed exactly once when the groups are processed
     * in parallel and the source and target match collections are the same.
     *
     * @param  allResults  creep correction results keyed by group (section) id, not z.
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
        for (final CanvasMatches cm : outsideMatches) {
            if (groupId.equals(cm.getpGroupId())) {
                allMatches.add(cm);
            }
        }

        LOG.info("transformMatchesForGroup: groupId={} has {} within-group matches and {} of {} outside-group matches with it as the pGroupId",
                 groupId, withinMatches.size(), allMatches.size() - withinMatches.size(), outsideMatches.size());

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
     * <br/><br/>
     * Match coordinates are in the space of the tile after it is normalized for matching.
     * This space includes all transforms except the last (alignment) transform.
     * The CC transform operates in this space, because it is inserted before the alignment transform.
     * <br/><br/>
     * Match coordinates are relative to the match frame origin of the tile.
     * The match frame origin is the min corner of the normalized tile bounds before the correction.
     * The CC transform varies with y, so it needs absolute coordinates.
     * Thus, this method adds the match frame origin, applies the CC transform,
     * and then subtracts the same match frame origin.
     * <br/><br/>
     * The CC transform also moves the min corner of the normalized tile bounds.
     * The amount depends on the amplitude of the mFOV.
     * Do not subtract the moved min corner: solvers apply the alignment transform directly
     * to match coordinates and ignore the min corner, so each corrected mFOV would be offset.
     */
    static void transformMatchCoordinates(final double[][] coords,
                                          final String tileId,
                                          final String groupId,
                                          final Map<String, List<MfovResult>> allResults) {

        // look up CC result for this tile's mFOV
        final String mfov = MultiSemUtilities.getMagcMfovForTileId(tileId);
        final List<MfovResult> layerResults = allResults.get(groupId);
        if (layerResults == null) {
            return;
        }

        MfovResult ccResult = null;
        for (final MfovResult r : layerResults) {
            if (r.mfov.equals(mfov) && r.correctionSpec != null) {
                ccResult = r;
                break;
            }
        }
        if (ccResult == null) {
            return;
        }

        final double[] origin = ccResult.tileIdToMatchFrameOrigin.get(tileId);
        if (origin == null) {
            throw new IllegalStateException("no match frame origin found for tile " + tileId +
                                            " in corrected mFOV " + mfov + " of group " + groupId);
        }

        final CoordinateTransform ccTransform = ccResult.correctionSpec.getNewInstance();
        for (int i = 0; i < coords[0].length; i++) {
            final double[] point = new double[] { coords[0][i] + origin[0], coords[1][i] + origin[1] };
            ccTransform.applyInPlace(point);
            coords[0][i] = point[0] - origin[0];
            coords[1][i] = point[1] - origin[1];
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

        /** [minX, minY] match frame origin for each corrected tile in the mFOV (empty if the mFOV was not corrected) */
        public final Map<String, double[]> tileIdToMatchFrameOrigin;

        MfovResult(final String mfov,
                   final double medianStretch,
                   final double stddev,
                   final double amplitude,
                   final int validPairs,
                   final int totalPairs,
                   final TransformSpec correctionSpec,
                   final String diagnosticMessage) {
            this(mfov, medianStretch, stddev, amplitude, validPairs, totalPairs, correctionSpec, diagnosticMessage,
                 new HashMap<>());
        }

        private MfovResult(final String mfov,
                           final double medianStretch,
                           final double stddev,
                           final double amplitude,
                           final int validPairs,
                           final int totalPairs,
                           final TransformSpec correctionSpec,
                           final String diagnosticMessage,
                           final HashMap<String, double[]> tileIdToMatchFrameOrigin) {
            this.mfov = mfov;
            this.medianStretch = medianStretch;
            this.stddev = stddev;
            this.amplitude = amplitude;
            this.validPairs = validPairs;
            this.totalPairs = totalPairs;
            this.isValid = correctionSpec != null;
            this.diagnosticMessage = diagnosticMessage;
            this.correctionSpec = correctionSpec;
            this.tileIdToMatchFrameOrigin = tileIdToMatchFrameOrigin;
        }

        static MfovResult invalid(final String mfov, final int totalPairs, final String reason) {
            return new MfovResult(mfov, Double.NaN, Double.NaN, Double.NaN, 0, totalPairs, null, reason);
        }

        /** @return a copy of this result with the specified match frame origins for its corrected tiles. */
        MfovResult withMatchFrameOrigin(final Map<String, double[]> tileIdToMatchFrameOrigin) {
            return new MfovResult(mfov, medianStretch, stddev, amplitude, validPairs, totalPairs, correctionSpec,
                                  diagnosticMessage, new HashMap<>(tileIdToMatchFrameOrigin));
        }

        public String toCsvRow() {
            return mfov + "," + medianStretch + "," + stddev + "," + amplitude + ","
                   + validPairs + "," + totalPairs + "," + isValid + "," + diagnosticMessage;
        }
    }

    /** A pair of tile IDs representing geometrically adjacent sFOVs, where the second tile is below the first. */
    static class TilePair {
        final String upperTileId;
        final String lowerTileId;

        TilePair(final String upperTileId, final String lowerTileId) {
            this.upperTileId = upperTileId;
            this.lowerTileId = lowerTileId;
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

package org.janelia.render.client.multisem;

import java.awt.Rectangle;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import mpicbg.models.AffineModel2D;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.TranslationModel2D;

import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.Matches;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.stage.StageMatcher;
import org.janelia.alignment.multisem.MultiSemUtilities;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.RenderDataClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Match data for a {@link MFOVPositionPair}.
 *
 * @author Eric Trautman
 */
public class MFOVPositionPairMatchData
        implements Serializable {

    /** Identifies a pair of (single-field-of-view) tiles within a multi-field-of-view group across all z-layers in a slab. */
    private final MFOVPositionPair positionPair;

    /** All tile pairs, connected and unconnected, across z for this position pair. */
    private final Set<OrderedCanvasIdPair> allPairsForPosition;

    /** Tile pairs that are unconnected (missing SIFT matches) across z for this position pair. */
    private final Set<OrderedCanvasIdPair> unconnectedPairsForPosition;

    private final Map<String, OrderedCanvasIdPair> groupIdToSameLayerPairForPosition;

    /** Tile specs for all tile pairs associated with this position. */
    private final Map<String, TileSpec> idToTileSpec;

    /** The first p-tile spec for this position (used to ensure consistency across all p-tile specs). */
    private TileSpec pFirstTileSpec;

    /** The first q-tile spec for this position (used to ensure consistency across all q-tile specs). */
    private TileSpec qFirstTileSpec;

    public MFOVPositionPairMatchData(final MFOVPositionPair positionPair) {
        this.positionPair = positionPair;
        this.allPairsForPosition = new HashSet<>();
        this.unconnectedPairsForPosition = new HashSet<>();
        this.groupIdToSameLayerPairForPosition = new HashMap<>();
        this.idToTileSpec = new HashMap<>();
    }

    public void addPair(final OrderedCanvasIdPair pair,
                        final TileSpec pTileSpec,
                        final TileSpec qTileSpec) throws IllegalArgumentException {
        this.allPairsForPosition.add(pair);
        if (allPairsForPosition.size() == 1) {
            this.pFirstTileSpec = pTileSpec;
            this.qFirstTileSpec = qTileSpec;
        } else {
            validateTileSpec(this.pFirstTileSpec, pTileSpec);
            validateTileSpec(this.qFirstTileSpec, qTileSpec);
        }
        this.idToTileSpec.put(pTileSpec.getTileId(), pTileSpec);
        this.idToTileSpec.put(qTileSpec.getTileId(), qTileSpec);
    }

    public void addUnconnectedPair(final OrderedCanvasIdPair unconnectedPair) {
        this.unconnectedPairsForPosition.add(unconnectedPair);
    }

    public boolean hasUnconnectedPairs() {
        return ! unconnectedPairsForPosition.isEmpty();
    }

    public void addSameLayerPair(final OrderedCanvasIdPair sameLayerPair) {
        if (sameLayerPair != null) {
            this.groupIdToSameLayerPairForPosition.put(sameLayerPair.getP().getGroupId(), sameLayerPair);
        }
    }

    @Override
    public String toString() {
        return "position: " + positionPair +
               ", unconnectedPairsSize: " + unconnectedPairsForPosition.size() +
               ", allPairsSize: " + allPairsForPosition.size();
    }

    /**
     * Derive matches for all unconnected pairs in this container.
     *
     * @param  matchClient                   client for fetching existing match data needed for derivation.
     * @param  sameLayerDerivedMatchWeight   weight for all derived same-layer matches
     *                                       (or null if same-layer matching should be skipped).
     * @param  crossLayerDerivedMatchWeight  weight for all derived cross-layer matches.
     *
     * @return list of derived matches.
     *
     * @throws IOException
     *   if any failures occur during derivation.
     */
    public List<CanvasMatches> deriveMatchesForUnconnectedPairs(final RenderDataClient matchClient,
                                                                final Double sameLayerDerivedMatchWeight,
                                                                final Double crossLayerDerivedMatchWeight,
                                                                final Double startPositionMatchWeight)
            throws IOException {

        LOG.info("deriveMatchesForUnconnectedPairs: entry, {}", this);

        final List<CanvasMatches> derivedMatchesList = new ArrayList<>();

        if (! unconnectedPairsForPosition.isEmpty()) {

            // 1. if same layer derivation is requested, try to patch with same layer matches
            final Set<OrderedCanvasIdPair> unconnectedPairsAfterStep1;
            if (sameLayerDerivedMatchWeight != null) {
                unconnectedPairsAfterStep1 =
                        deriveMatchesUsingDataFromSameLayer(matchClient,
                                                            sameLayerDerivedMatchWeight,
                                                            derivedMatchesList,
                                                            unconnectedPairsForPosition);
            } else {
                unconnectedPairsAfterStep1 = unconnectedPairsForPosition;
            }

            // 2. if cross layer derivation is requested, try to patch remaining unconnected pairs with cross layer data
            Set<OrderedCanvasIdPair> unconnectedPairsAfterStep2 = unconnectedPairsAfterStep1;
            if ((crossLayerDerivedMatchWeight != null) && (! unconnectedPairsAfterStep1.isEmpty())) {
                if (allPairsForPosition.size() <= unconnectedPairsForPosition.size()) {
                    LOG.warn("because {} out of {} pairs for position {} are unconnected, cross layer derivation cannot be done",
                             unconnectedPairsForPosition.size(), allPairsForPosition.size(), positionPair);
                } else {
                    unconnectedPairsAfterStep2 =
                            deriveMatchesUsingDataFromOtherLayers(matchClient,
                                                                  crossLayerDerivedMatchWeight,
                                                                  derivedMatchesList,
                                                                  unconnectedPairsAfterStep1);
                }
            } else {
                LOG.info("deriveMatchesForUnconnectedPairs: skipping cross layer derivation, {} unconnected pairs remain, crossLayerDerivedMatchWeight={}",
                         unconnectedPairsAfterStep1.size(), crossLayerDerivedMatchWeight);
            }
            
            // 3. if start position derivation is requested, patch remaining unconnected pairs with start position data
            if ((! unconnectedPairsAfterStep2.isEmpty()) &&
                (startPositionMatchWeight != null)) {
                deriveMatchesUsingStartPositions(startPositionMatchWeight,
                                                 derivedMatchesList,
                                                 unconnectedPairsAfterStep2);
            } else {
                LOG.info("deriveMatchesForUnconnectedPairs: skipping start position derivation, {} unconnected pairs remain, startPositionMatchWeight={}",
                         unconnectedPairsAfterStep2.size(), startPositionMatchWeight);
            }

        } else {
            LOG.info("deriveMatchesForUnconnectedPairs: all pairs for {} are connected, nothing to derive", this);
        }

        LOG.info("deriveMatchesForUnconnectedPairs: exit, returning matches for {}", this);

        return derivedMatchesList;
    }

    private Set<OrderedCanvasIdPair> buildRemainingPairsSet(final Set<OrderedCanvasIdPair> pairsBeforeRemoval,
                                                            final Set<OrderedCanvasIdPair> pairsToRemove) {
        return pairsBeforeRemoval.stream().filter(p -> ! pairsToRemove.contains(p)).collect(Collectors.toSet());
    }

    /** @return the set of unconnected pairs that were not patched with same layer data. */
    private Set<OrderedCanvasIdPair> deriveMatchesUsingDataFromSameLayer(final RenderDataClient matchClient,
                                                                         final double sameLayerDerivedMatchWeight,
                                                                         final List<CanvasMatches> derivedMatchesList,
                                                                         final Set<OrderedCanvasIdPair> unconnectedPairsAtStart)
            throws IOException {

        LOG.info("deriveMatchesUsingDataFromSameLayer: entry, {} ", this);

        final Set<OrderedCanvasIdPair> pairsConnectedInThisStep = new HashSet<>();

        for (final OrderedCanvasIdPair pair : unconnectedPairsAtStart) {

            final CanvasId unconnectedP = pair.getP();
            final CanvasId unconnectedQ = pair.getQ();
            final OrderedCanvasIdPair sameLayerPair = groupIdToSameLayerPairForPosition.get(unconnectedP.getGroupId());

            if (sameLayerPair != null) {
                // retrieve matches for same SFOV pair in another MFOV in the same z layer
                final CanvasId p = sameLayerPair.getP();
                final CanvasId q = sameLayerPair.getQ();
                final CanvasMatches canvasMatches = matchClient.getMatchesBetweenTiles(p.getGroupId(),
                                                                                       p.getId(),
                                                                                       q.getGroupId(),
                                                                                       q.getId());
                // copy matches but override weights
                final Matches matches = canvasMatches.getMatches();
                final double[] derivedWeightList = new double[matches.getWs().length];
                Arrays.fill(derivedWeightList, sameLayerDerivedMatchWeight);

                final Matches derivedMatches = new Matches(matches.getPs(), matches.getQs(), derivedWeightList);

                derivedMatchesList.add(new CanvasMatches(unconnectedP.getGroupId(),
                                                         unconnectedP.getId(),
                                                         unconnectedQ.getGroupId(),
                                                         unconnectedQ.getId(),
                                                         derivedMatches));

                pairsConnectedInThisStep.add(pair); // keep track of substituted pairs
            }

        }

        final Set<OrderedCanvasIdPair> remainingUnconnectedPairs = buildRemainingPairsSet(unconnectedPairsAtStart,
                                                                                          pairsConnectedInThisStep);

        LOG.info("deriveMatchesUsingDataFromSameLayer: exit, added matches for {} unconnected pairs, {} unconnected pairs remain, {}",
                 pairsConnectedInThisStep.size(), remainingUnconnectedPairs.size(), this);

        return remainingUnconnectedPairs;
    }

    /** @return the set of unconnected pairs that were not patched with cross layer data. */
    private Set<OrderedCanvasIdPair> deriveMatchesUsingDataFromOtherLayers(final RenderDataClient matchClient,
                                                                           final double derivedMatchWeight,
                                                                           final List<CanvasMatches> derivedMatchesList,
                                                                           final Set<OrderedCanvasIdPair> unconnectedPairsAtStart)
            throws IOException {

        LOG.info("deriveMatchesUsingDataFromOtherLayers: entry, {}", this);

        final Set<OrderedCanvasIdPair> pairsConnectedInThisStep = new HashSet<>();
        final List<PointMatch> existingCornerMatchList = new ArrayList<>();

        // loop through all tile pairs (connected and unconnected) across z for this position
        for (final OrderedCanvasIdPair pair : allPairsForPosition) {

            if (! unconnectedPairsForPosition.contains(pair)) {
                final CanvasId p = pair.getP();
                final CanvasId q = pair.getQ();

                // constructor normalizes the p/q order so they will be flipped if necessary
                final CanvasMatches canvasMatches = matchClient.getMatchesBetweenTiles(p.getGroupId(),
                                                                                       p.getId(),
                                                                                       q.getGroupId(),
                                                                                       q.getId());
                // this is specific for a z
                // AffineModel and RigidModel introduce artifacts because the point matches are far away from the corners
                final TranslationModel2D existingMatchModel = new TranslationModel2D(); // new AffineModel2D(); new RigidModel2D();
                MultiSemUtilities.fitModelAndLogStats(existingMatchModel,
                                                      canvasMatches,
                                              "existing pair " + pair);

                final TileSpec pTileSpec = idToTileSpec.get(p.getId());
                final TileSpec qTileSpec = idToTileSpec.get(q.getId());
                final List<Point> pLensCorrectedCorners = pTileSpec.getMatchingTransformedCornerPoints();
                final List<Point> qLensCorrectedCorners = qTileSpec.getMatchingTransformedCornerPoints();
                for (int i = 0; i < pLensCorrectedCorners.size(); i++) {
                    final Point pCorner = pLensCorrectedCorners.get(i);
                    final Point qCorner = qLensCorrectedCorners.get(i);
                    qCorner.apply(existingMatchModel); // apply to q

                    //System.out.println( "p=" + Util.printCoordinates(pCorner.getW()) + ", q=" + Util.printCoordinates(qCorner.getW()));
                    existingCornerMatchList.add(new PointMatch(pCorner, qCorner));
                }
            }
        }

        // fit a model to all corner points across z that had pointmatches
        // TODO: RANSAC?
        // TODO: compute errors and display?
        final AffineModel2D existingCornerMatchModel = new AffineModel2D();
        try {
            MultiSemUtilities.fitModelAndLogStats(existingCornerMatchModel, existingCornerMatchList, "corner matches");
        } catch (final Exception e) {
            throw new IOException("failed to fit model for corner matches", e);
        }

        // for each missing pair do
        for (final OrderedCanvasIdPair pair : unconnectedPairsForPosition.stream().sorted().collect(Collectors.toList())) {
            // if the pair was not already patched from a prior step ...
            if (unconnectedPairsAtStart.contains(pair)) {

                final TileSpec pTileSpec = idToTileSpec.get(pair.getP().getId());
                final TileSpec qTileSpec = idToTileSpec.get(pair.getQ().getId());
                // Note: derivedMatchWeight is included in missingCornerMatchList PointMatch constructor (above)
                //       and then saved with converted canvas matches here
                derivedMatchesList.add(
                        MultiSemUtilities.buildPointMatches(pair,
                                                            MultiSemUtilities.getMatchingTransformedCornersForTile(pTileSpec, CORNER_MARGIN),
                                                            MultiSemUtilities.getMatchingTransformedCornersForTile(qTileSpec, CORNER_MARGIN),
                                                            existingCornerMatchModel,
                                                            derivedMatchWeight));
                pairsConnectedInThisStep.add(pair);
            }
        }

        final Set<OrderedCanvasIdPair> remainingUnconnectedPairs = buildRemainingPairsSet(unconnectedPairsAtStart,
                                                                                          pairsConnectedInThisStep);

        LOG.info("deriveMatchesUsingDataFromOtherLayers: exit, added matches for {} unconnected pairs, {} unconnected pairs remain, {}",
                 pairsConnectedInThisStep.size(), remainingUnconnectedPairs.size(), this);

        return remainingUnconnectedPairs;
    }

    public void deriveMatchesUsingStartPositions(final double derivedMatchWeight,
                                                 final List<CanvasMatches> derivedMatchesList,
                                                 final Set<OrderedCanvasIdPair> unconnectedPairsAtStart)
            throws IOException {

        LOG.info("deriveMatchesUsingStartPositions: entry, {}", this);

        for (final OrderedCanvasIdPair pair : unconnectedPairsAtStart) {
            final CanvasId p = pair.getP();
            final TileSpec pTileSpec = idToTileSpec.get(p.getId());
            final Rectangle pWorldBounds = pTileSpec.toTileBounds().toRectangle();

            final CanvasId q = pair.getQ();
            final TileSpec qTileSpec = idToTileSpec.get(q.getId());
            final Rectangle qWorldBounds = qTileSpec.toTileBounds().toRectangle();

            final CanvasMatches startPositionMatches =
                    StageMatcher.generateStartPositionOverlapMatches(p,
                                                                     pWorldBounds,
                                                                     q,
                                                                     qWorldBounds,
                                                                     derivedMatchWeight);
            derivedMatchesList.add(startPositionMatches);
        }

        LOG.info("deriveMatchesUsingStartPositions: exit, added matches for {} unconnected pairs, {}",
                 unconnectedPairsAtStart.size(), this);
    }

    private static void validateTileSpec(final TileSpec expected,
                                         final TileSpec actual) throws IllegalArgumentException {
        if (expected.getWidth() != actual.getWidth()) {
            throw new IllegalArgumentException(
                    "tile " + expected.getTileId() + " width " + expected.getWidth() +
                    " differs from tile " + actual.getTileId() + " width " + actual.getWidth());
        } else if (expected.getHeight() != actual.getHeight()) {
            throw new IllegalArgumentException(
                    "tile " + expected.getTileId() + " height " + expected.getHeight() +
                    " differs from tile " + actual.getTileId() + " height " + actual.getHeight());
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(MFOVPositionPairMatchData.class);

    /** Move corner match points slightly inside tile to improve visualization. */
    private static final int CORNER_MARGIN = 50;
}

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
            final Set<OrderedCanvasIdPair> unconnectedPairsWithSameLayerSubstitute;
            if (sameLayerDerivedMatchWeight != null) {
                unconnectedPairsWithSameLayerSubstitute =
                        deriveMatchesUsingDataFromSameLayer(matchClient,
                                                            sameLayerDerivedMatchWeight,
                                                            derivedMatchesList);
            } else {
                unconnectedPairsWithSameLayerSubstitute = new HashSet<>();
            }

            final Set<OrderedCanvasIdPair> unconnectedPairsAfterStep1 =
                    unconnectedPairsForPosition.stream().filter(
                                    p -> ! unconnectedPairsWithSameLayerSubstitute.contains(p)
                            ).collect(Collectors.toSet());

            LOG.info("deriveMatchesForUnconnectedPairs: after same layer derivation, {} unconnected pairs remain",
                     unconnectedPairsAfterStep1.size());

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
                                                                  unconnectedPairsWithSameLayerSubstitute);
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
            LOG.info("all pairs for {} are connected, nothing to derive", this);
        }

        LOG.info("deriveMatchesForUnconnectedPairs: exit, returning matches for {}", this);

        return derivedMatchesList;
    }

    private Set<OrderedCanvasIdPair> deriveMatchesUsingDataFromSameLayer(final RenderDataClient matchClient,
                                                                         final double sameLayerDerivedMatchWeight,
                                                                         final List<CanvasMatches> derivedMatchesList)
            throws IOException {

        LOG.info("deriveMatchesUsingDataFromSameLayer: entry, {} ", this);

        final Set<OrderedCanvasIdPair> unconnectedPairsWithSameLayerSubstitute = new HashSet<>();

        for (final OrderedCanvasIdPair unconnectedPair : unconnectedPairsForPosition) {

            final CanvasId unconnectedP = unconnectedPair.getP();
            final CanvasId unconnectedQ = unconnectedPair.getQ();
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

                unconnectedPairsWithSameLayerSubstitute.add(unconnectedPair); // keep track of substituted pairs
            }

        }

        LOG.info("deriveMatchesUsingDataFromSameLayer: exit, added matches for {} unconnected pairs, {}",
                 unconnectedPairsWithSameLayerSubstitute.size(), this);

        return unconnectedPairsWithSameLayerSubstitute;
    }

    private Set<OrderedCanvasIdPair> deriveMatchesUsingDataFromOtherLayers(final RenderDataClient matchClient,
                                                                           final double derivedMatchWeight,
                                                                           final List<CanvasMatches> derivedMatchesList,
                                                                           final Set<OrderedCanvasIdPair> unconnectedPairsWithSameLayerSubstitute)
            throws IOException {

        LOG.info("deriveMatchesUsingDataFromOtherLayers: entry, {}", this);

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
        int addedPairCount = 0;
        for (final OrderedCanvasIdPair pair : unconnectedPairsForPosition.stream().sorted().collect(Collectors.toList())) {
            // if the pair was not already patched from same layer ...
            if (! unconnectedPairsWithSameLayerSubstitute.contains(pair)) {

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
                addedPairCount++;
            }
        }

        final Set<OrderedCanvasIdPair> remainingUnconnectedPairsForPosition = new HashSet<>(unconnectedPairsForPosition);
        remainingUnconnectedPairsForPosition.removeAll(unconnectedPairsWithSameLayerSubstitute);

        LOG.info("deriveMatchesUsingDataFromOtherLayers: exit, {} unconnected pairs remain, added matches for {} unconnected pairs, {}",
                 remainingUnconnectedPairsForPosition.size(), addedPairCount, this);

        return remainingUnconnectedPairsForPosition;
    }

    public void deriveMatchesUsingStartPositions(final double derivedMatchWeight,
                                                 final List<CanvasMatches> derivedMatchesList,
                                                 final Set<OrderedCanvasIdPair> unconnectedPairsForPositionAfterCrossDerivation)
            throws IOException {

        LOG.info("deriveMatchesUsingStartPositions: entry, {}", this);

        final int numberOfMatchPoints = 4;
        final double[] derivedWeightList = new double[numberOfMatchPoints];
        Arrays.fill(derivedWeightList, derivedMatchWeight);

        for (final OrderedCanvasIdPair pair : unconnectedPairsForPositionAfterCrossDerivation) {

            final CanvasId p = pair.getP();
            final TileSpec pTileSpec = idToTileSpec.get(p.getId());
            final Rectangle pWorldBounds = pTileSpec.toTileBounds().toRectangle();

            final CanvasId q = pair.getQ();
            final TileSpec qTileSpec = idToTileSpec.get(q.getId());
            final Rectangle qWorldBounds = qTileSpec.toTileBounds().toRectangle();

            final Rectangle worldOverlap = pWorldBounds.intersection(qWorldBounds);
            LOG.info("deriveMatchesUsingStartPositions: overlap between {} and {} is {}",
                     pTileSpec.getTileId(), qTileSpec.getTileId(), worldOverlap);

            if (worldOverlap.height <= 0 || worldOverlap.width <= 0) {
                throw new IOException("no overlap between " + pTileSpec.getTileId() + " and " + qTileSpec.getTileId());
            }

            final double[][] worldOverlapPoints = new double[][] {
                    { worldOverlap.x, worldOverlap.y },
                    { worldOverlap.x + worldOverlap.width, worldOverlap.y },
                    { worldOverlap.x + worldOverlap.width, worldOverlap.y + worldOverlap.height },
                    { worldOverlap.x, worldOverlap.y + worldOverlap.height }
            };

            final double[][] pMatches = new double[2][worldOverlapPoints.length];
            final double[][] qMatches = new double[2][worldOverlapPoints.length];

            for (int i = 0; i < worldOverlapPoints.length; i++) {
                final double[] worldOverlapCorner = worldOverlapPoints[i];
                pMatches[0][i] = worldOverlapCorner[0] - pWorldBounds.x;
                pMatches[1][i] = worldOverlapCorner[1] - pWorldBounds.y;
                qMatches[0][i] = worldOverlapCorner[0] - qWorldBounds.x;
                qMatches[1][i] = worldOverlapCorner[1] - qWorldBounds.y;
            }

            // constructor normalizes the p/q order so they will be flipped if necessary
            final CanvasMatches startPositionMatches = new CanvasMatches(p.getGroupId(),
                                                                         p.getId(),
                                                                         q.getGroupId(),
                                                                         q.getId(),
                                                                         new Matches(pMatches,
                                                                                     qMatches,
                                                                                     derivedWeightList));
            derivedMatchesList.add(startPositionMatches);
        }

        LOG.info("deriveMatchesUsingStartPositions: exit, added matches for {} unconnected pairs, {}",
                 unconnectedPairsForPositionAfterCrossDerivation.size(), this);
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

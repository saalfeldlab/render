package org.janelia.render.client.multisem;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
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
import org.janelia.alignment.match.CanvasMatchResult;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.OrderedCanvasIdPair;
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
        return unconnectedPairsForPosition.size() > 0;
    }

    @Override
    public String toString() {
        return "position: " + positionPair +
               ", unconnectedPairsSize: " + unconnectedPairsForPosition.size() +
               ", allPairsSize: " + allPairsForPosition.size();
    }

    public List<CanvasMatches> deriveMatchesForUnconnectedPairs(final RenderDataClient matchClient,
                                                                final double derivedMatchWeight)
            throws IOException {

        LOG.info("deriveMatchesForUnconnectedPairs: entry, {}", this);

        final List<CanvasMatches> derivedMatchesList = new ArrayList<>();

        if ((unconnectedPairsForPosition.size() == 0) ||
            (allPairsForPosition.size() <= unconnectedPairsForPosition.size())) {
            throw new IOException("nothing to derive for " + this);
        }

        final List<PointMatch> existingCornerMatchList = new ArrayList<>();

        // loop through all tile pairs (connected and unconnected) across z for this position
        for (final OrderedCanvasIdPair pair : allPairsForPosition) {

            if (! unconnectedPairsForPosition.contains(pair)) {
                final CanvasId p = pair.getP();
                final CanvasId q = pair.getQ();

                // there is magic in here that fixes the order (String comparison)
                final CanvasMatches canvasMatches = matchClient.getMatchesBetweenTiles(p.getGroupId(),
                                                                                       p.getId(),
                                                                                       q.getGroupId(),
                                                                                       q.getId());
                // this is specific for a z
                List<PointMatch> existingMatchList =
                        CanvasMatchResult.convertMatchesToPointMatchList(canvasMatches.getMatches());

                // because fit() maps p.l to q.w, we need to apply the model to q
                // we want the model that maps q onto p
                // alternatively invert the model or apply to p
                existingMatchList = (List< PointMatch >)PointMatch.flip( existingMatchList );

                // PointMatch(p,q), will find a model that maps local coord of p to world coord of q

                // AffineModel and RigidModel introduce artifacts because the pointmatches are far away from the corners
                //AffineModel2D existingMatchModel = new AffineModel2D();
                //RigidModel2D existingMatchModel = new RigidModel2D();
                final TranslationModel2D existingMatchModel = new TranslationModel2D();
                try {
                    existingMatchModel.fit(existingMatchList);
                    
                    
                    double error = 0;
        			double maxError = 0;
        			
        			for ( final PointMatch pm : existingMatchList )
        			{
        				pm.apply( existingMatchModel );
        				error += pm.getDistance();
        				maxError = Math.max( maxError, pm.getDistance() );
        			}

        			error /= existingMatchList.size();
                    
        			LOG.debug("deriveMatchesForUnconnectedPairs: after fit, error is {} and existingMatchModel is {}",
                              error, existingMatchModel);
                } catch (final Exception e) {
                    throw new IOException("failed to fit model for pair " + pair, e);
                }

                // because fit() maps p.l to q.w, we need to apply the model to q
                // we want the model that maps q onto p
                //existingMatchModel = existingMatchModel.createInverse();

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
            existingCornerMatchModel.fit(existingCornerMatchList);

            LOG.debug("deriveMatchesForUnconnectedPairs: existingCornerMatchModel after fit is {}",
                      existingCornerMatchModel);

            // compute the error & maxError, remember worst for now?
			double error = 0;
			double maxError = 0;
			
			for ( final PointMatch pm : existingCornerMatchList )
			{
				pm.apply( existingCornerMatchModel );
				error += pm.getDistance();
				maxError = Math.max( maxError, pm.getDistance() );
			}

			error /= existingCornerMatchList.size();

            LOG.info("deriveMatchesForUnconnectedPairs: existingCornerMatchModel error is {} and maxError is {}",
                      error, maxError);

        } catch (final Exception e) {
            throw new IOException("failed to fit model for corner matches", e);
        }

        // for each missing pair do
        for (final OrderedCanvasIdPair pair : unconnectedPairsForPosition.stream().sorted().collect(Collectors.toList())) {

            final List<PointMatch> missingCornerMatchList = new ArrayList<>();

            final CanvasId p = pair.getP();
            final CanvasId q = pair.getQ();

            final TileSpec pTileSpec = idToTileSpec.get(p.getId());
            final TileSpec qTileSpec = idToTileSpec.get(q.getId());

            final List<Point> pLensCorrectedCorners = pTileSpec.getMatchingTransformedCornerPoints();
            final List<Point> qLensCorrectedCorners = qTileSpec.getMatchingTransformedCornerPoints();
            for (int i = 0; i < pLensCorrectedCorners.size(); i++) {
                final Point pCorner = pLensCorrectedCorners.get(i);
                final Point qCorner = qLensCorrectedCorners.get(i);
                qCorner.apply(existingCornerMatchModel); // wrong? should be p or PointMatches should be (q,p) or the inverse of the model
                final Point transformedQCorner = new Point(qCorner.getW()); // need to use q world coordinates
                missingCornerMatchList.add(new PointMatch(pCorner, transformedQCorner, derivedMatchWeight));
            }

            // Note: derivedMatchWeight is included in missingCornerMatchList PointMatch constructor (above)
            //       and then saved with converted canvas matches here
            derivedMatchesList.add(
                    new CanvasMatches(p.getGroupId(),
                                      p.getId(),
                                      q.getGroupId(),
                                      q.getId(),
                                      CanvasMatchResult.convertPointMatchListToMatches(missingCornerMatchList,
                                                                                       1.0)));
        }

        LOG.info("deriveMatchesForUnconnectedPairs: exit, returning matches for {}", this);

        return derivedMatchesList;
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
}

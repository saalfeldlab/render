package org.janelia.render.client.multisem;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mpicbg.models.AffineModel2D;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;

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

    private final MFOVPositionPair positionPair;
    private final Set<OrderedCanvasIdPair> potentialPairs;
    private final Set<OrderedCanvasIdPair> missingPairs;
    private final Map<String, TileSpec> idToTileSpec;
    private TileSpec pFirstTileSpec;
    private TileSpec qFirstTileSpec;

    public MFOVPositionPairMatchData(final MFOVPositionPair positionPair) {
        this.positionPair = positionPair;
        this.potentialPairs = new HashSet<>();
        this.missingPairs = new HashSet<>();
        this.idToTileSpec = new HashMap<>();
    }

    public void addPotentialPair(final OrderedCanvasIdPair potentialPair,
                                 final TileSpec pTileSpec,
                                 final TileSpec qTileSpec) throws IllegalArgumentException {
        this.potentialPairs.add(potentialPair);
        if (potentialPairs.size() == 1) {
            this.pFirstTileSpec = pTileSpec;
            this.qFirstTileSpec = qTileSpec;
        } else {
            validateTileSpec(this.pFirstTileSpec, pTileSpec);
            validateTileSpec(this.qFirstTileSpec, qTileSpec);
        }
        this.idToTileSpec.put(pTileSpec.getTileId(), pTileSpec);
        this.idToTileSpec.put(qTileSpec.getTileId(), qTileSpec);
    }

    public void addMissingPair(final OrderedCanvasIdPair missingPair) {
        this.missingPairs.add(missingPair);
    }

    public boolean hasMissingPairs() {
        return missingPairs.size() > 0;
    }

    @Override
    public String toString() {
        return "position: " + positionPair + ", missingPairs.size: " + missingPairs.size() +
               ", potentialPairs.size: " + potentialPairs.size();
    }

    public List<CanvasMatches> deriveMatchesForMissingPairs(final RenderDataClient matchClient,
                                                            final double derivedMatchWeight)
            throws IOException {

        LOG.info("deriveMatchesForMissingPairs: entry, {}", this);

        final List<CanvasMatches> derivedMatchesList = new ArrayList<>();

        if ((missingPairs.size() == 0) || (potentialPairs.size() <= missingPairs.size())) {
            throw new IOException("nothing to derive for " + this);
        }

        final List<PointMatch> existingCornerMatchList = new ArrayList<>();

        // a single pair of tiles across all z layers
        for (final OrderedCanvasIdPair pair : potentialPairs) {
            if (! missingPairs.contains(pair)) { // missingPairs are those that are not connected
                final CanvasId p = pair.getP();
                final CanvasId q = pair.getQ();
                final CanvasMatches canvasMatches = matchClient.getMatchesBetweenTiles(p.getGroupId(),
                                                                                       p.getId(),
                                                                                       q.getGroupId(),
                                                                                       q.getId());
                // this is specific for a z
                final List<PointMatch> existingMatchList =
                        CanvasMatchResult.convertMatchesToPointMatchList(canvasMatches.getMatches());

                // PointMatch(p,q), will find a model that maps local coord of p to world coord of q
                final AffineModel2D existingMatchModel = new AffineModel2D();
                try {
                    existingMatchModel.fit(existingMatchList);
                } catch (final Exception e) {
                    throw new IOException("failed to fit model for pair " + pair, e);
                }

                final TileSpec pTileSpec = idToTileSpec.get(p.getId());
                final TileSpec qTileSpec = idToTileSpec.get(q.getId());
                final List<Point> pLensCorrectedCorners = pTileSpec.getMatchingTransformedCornerPoints();
                final List<Point> qLensCorrectedCorners = qTileSpec.getMatchingTransformedCornerPoints();
                for (int i = 0; i < pLensCorrectedCorners.size(); i++) {
                    final Point pCorner = pLensCorrectedCorners.get(i);
                    final Point qCorner = qLensCorrectedCorners.get(i);
                    qCorner.apply(existingMatchModel); // wrong? should be p or PointMatches should be (q,p) or the inverse of the model
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

        } catch (final Exception e) {
            throw new IOException("failed to fit model for corner matches", e);
        }

        // for each missing pair do
        for (final OrderedCanvasIdPair pair : missingPairs) {

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

            // TODO: weights?
            derivedMatchesList.add(
                    new CanvasMatches(p.getGroupId(),
                                      p.getId(),
                                      q.getGroupId(),
                                      q.getId(),
                                      CanvasMatchResult.convertPointMatchListToMatches(missingCornerMatchList,
                                                                                       1.0)));
        }

        LOG.info("deriveMatchesForMissingPairs: exit, returning matches for {}", this);

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

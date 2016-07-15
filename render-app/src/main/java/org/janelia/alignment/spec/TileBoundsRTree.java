package org.janelia.alignment.spec;

import com.github.davidmoten.rtree.Entry;
import com.github.davidmoten.rtree.RTree;
import com.github.davidmoten.rtree.geometry.Circle;
import com.github.davidmoten.rtree.geometry.Geometries;
import com.github.davidmoten.rtree.geometry.Geometry;
import com.github.davidmoten.rtree.geometry.Rectangle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;

/**
 * RTree collection of {@link TileBounds} instances for (in-memory) geometric searching.
 *
 * @author Eric Trautman
 */
public class TileBoundsRTree {

    private final List<TileBounds> tileBoundsList;
    private final RTree<TileBounds, Geometry> tree;

    /**
     * Construct a tree from the specified list of tile bounds.
     *
     * @param  tileBoundsList  list of bounds onjects.
     */
    public TileBoundsRTree(final List<TileBounds> tileBoundsList) {

        this.tileBoundsList = tileBoundsList;

        RTree<TileBounds, Geometry> tree = RTree.create();
        for (final TileBounds tileBounds : tileBoundsList) {
            tree = tree.add(tileBounds,
                            Geometries.rectangle(tileBounds.getMinX(),
                                                 tileBounds.getMinY(),
                                                 tileBounds.getMaxX(),
                                                 tileBounds.getMaxY()));
        }
        this.tree = tree;

        LOG.debug("constructed tree for {} tiles", tileBoundsList.size());
    }

    /**
     * @return all tiles that intersect the specified bounding box.
     */
    public List<TileBounds> findTilesInBox(final double minX,
                                           final double minY,
                                           final double maxX,
                                           final double maxY) {

        final Rectangle rectangle = Geometries.rectangle(minX, minY, maxX, maxY);
        final Observable<Entry<TileBounds, Geometry>> searchResults = tree.search(rectangle);
        return convertResultsToList(searchResults);
    }

    /**
     * @return all tiles that intersect the specified circle.
     */
    public List<TileBounds> findTilesInCircle(final double centerX,
                                              final double centerY,
                                              final double radius) {
        return findTilesInCircle(Geometries.circle(centerX, centerY, radius));
    }

    /**
     * @return all tiles that intersect the specified circle.
     */
    public List<TileBounds> findTilesInCircle(final Circle circle) {
        final Observable<Entry<TileBounds, Geometry>> searchResults = tree.search(circle);
        return convertResultsToList(searchResults);
    }

    /**
     * @param  neighborTrees         list of trees for all neighboring sections to include in the pairing process.
     *
     * @param  neighborRadiusFactor  applied to max(width, height) of each tile
     *                               to determine radius for locating neighbor tiles.
     *
     * @return set of distinct neighbor pairs between this tree's tiles and the specified neighbor trees' tiles.
     */
    public Set<OrderedCanvasIdPair> getCircleNeighbors(final List<TileBoundsRTree> neighborTrees,
                                                       final double neighborRadiusFactor) {

        String firstTileId = null;
        if (tileBoundsList.size() > 0) {
            firstTileId = tileBoundsList.get(0).getTileId();
        }

        LOG.debug("getCircleNeighbors: entry, {} tiles, {} neighborTrees, firstTileId is {}",
                  tileBoundsList.size(), neighborTrees.size(), firstTileId);

        final Set<OrderedCanvasIdPair> neighborTileIdPairs = new HashSet<>(50000);

        double tileWidth;
        double tileHeight;
        double centerX;
        double centerY;
        double radius;
        Circle circle;
        List<TileBounds> searchResults;
        for (final TileBounds tileBounds : tileBoundsList) {

            tileWidth = tileBounds.getDeltaX();
            tileHeight = tileBounds.getDeltaY();

            centerX = tileBounds.getMinX() + (tileWidth / 2);
            centerY = tileBounds.getMinY() + (tileHeight / 2);
            radius = Math.max(tileWidth, tileHeight) * neighborRadiusFactor;

            circle = Geometries.circle(centerX, centerY, radius);

            searchResults = findTilesInCircle(circle);

            neighborTileIdPairs.addAll(getDistinctPairs(tileBounds, searchResults));

            for (final TileBoundsRTree neighborTree : neighborTrees) {
                searchResults = neighborTree.findTilesInCircle(circle);
                neighborTileIdPairs.addAll(getDistinctPairs(tileBounds, searchResults));
            }
        }

        LOG.debug("getCircleNeighbors: exit, returning {} pairs", neighborTileIdPairs.size());

        return neighborTileIdPairs;
    }

    /**
     * @return distinct set of pairs of the fromTile with each toTile.
     *         If the fromTile is in the toTiles list, it is ignored (fromTile won't be paired with itself).
     */
    public static Set<OrderedCanvasIdPair> getDistinctPairs(final TileBounds fromTile,
                                                            final List<TileBounds> toTiles) {
        final Set<OrderedCanvasIdPair> pairs = new HashSet<>(toTiles.size() * 2);
        final String pTileId = fromTile.getTileId();
        final CanvasId p = new CanvasId(pTileId);
        String qTileId;
        for (final TileBounds toTile : toTiles) {
            qTileId = toTile.getTileId();
            if (! pTileId.equals(qTileId)) {
                pairs.add(new OrderedCanvasIdPair(p, new CanvasId(qTileId)));
            }
        }
        return pairs;
    }

    private List<TileBounds> convertResultsToList(final Observable<Entry<TileBounds, Geometry>> searchResults) {

        final List<TileBounds> matchingTiles = new ArrayList<>();

        // TODO: make sure use of toBlocking() here is appropriate

        final List<Entry<TileBounds, Geometry>> collectedResultList = searchResults.toList().toBlocking().single();
        for (final Entry<TileBounds, Geometry> entry : collectedResultList) {
            matchingTiles.add(entry.value());
        }

        return matchingTiles;
    }

    private static final Logger LOG = LoggerFactory.getLogger(TileBoundsRTree.class);

}

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
    public Set<TileIdPair> getCircleNeighborTileIdPairs(final List<TileBoundsRTree> neighborTrees,
                                                        final double neighborRadiusFactor) {

        String firstTileId = null;
        if (tileBoundsList.size() > 0) {
            firstTileId = tileBoundsList.get(0).getTileId();
        }

        LOG.debug("getCircleNeighborTileIdPairs: entry, {} tiles, {} neighborTrees, firstTileId is {}",
                  tileBoundsList.size(), neighborTrees.size(), firstTileId);

        final Set<TileIdPair> neighborTileIdPairs = new HashSet<>(50000);

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

            neighborTileIdPairs.addAll(TileIdPair.getTileIdPairs(tileBounds, searchResults));

            for (final TileBoundsRTree neighborTree : neighborTrees) {
                searchResults = neighborTree.findTilesInCircle(circle);
                neighborTileIdPairs.addAll(TileIdPair.getTileIdPairs(tileBounds, searchResults));
            }
        }

        LOG.debug("getCircleNeighborTileIdPairs: exit, returning {} pairs", neighborTileIdPairs.size());

        return neighborTileIdPairs;
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

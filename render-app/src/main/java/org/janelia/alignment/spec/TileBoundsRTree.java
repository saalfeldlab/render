package org.janelia.alignment.spec;

import com.github.davidmoten.rtree.Entry;
import com.github.davidmoten.rtree.RTree;
import com.github.davidmoten.rtree.geometry.Circle;
import com.github.davidmoten.rtree.geometry.Geometries;
import com.github.davidmoten.rtree.geometry.Geometry;
import com.github.davidmoten.rtree.geometry.Rectangle;

import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
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

    private final Double z;
    private final List<TileBounds> tileBoundsList;
    private RTree<TileBounds, Geometry> tree;

    /**
     * Construct a tree from the specified list of tile bounds.
     *
     * @param  z               z value for all tiles.
     * @param  tileBoundsList  list of bounds objects.
     */
    public TileBoundsRTree(final Double z,
                           final List<TileBounds> tileBoundsList) {

        this.z = z;
        this.tileBoundsList = new ArrayList<>(tileBoundsList.size());
        this.tree = RTree.create();
        for (final TileBounds tileBounds : tileBoundsList) {
            addTile(tileBounds);
        }

        LOG.debug("constructed tree for {} tiles", tileBoundsList.size());
    }

    /**
     * Add a tile to this tree.
     *
     * @param  tileBounds  bounds for the tile.
     */
    public void addTile(final TileBounds tileBounds) {
        tileBoundsList.add(tileBounds);
        tree = tree.add(tileBounds,
                        Geometries.rectangle(tileBounds.getMinX(),
                                             tileBounds.getMinY(),
                                             tileBounds.getMaxX(),
                                             tileBounds.getMaxY()));
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
     * @return list of tiles that are completely obscured by other tiles.
     *         Assumes tiles with lexicographically greater tileId values are drawn on top.
     */
    public List<TileBounds> findCompletelyObscuredTiles() {
        final List<TileBounds> completelyObscuredTiles = new ArrayList<>(tileBoundsList.size());
        List<TileBounds> intersectingTiles;
        for (final TileBounds tile : tileBoundsList) {
            intersectingTiles = findTilesInBox(tile.getMinX(), tile.getMinY(),
                                               tile.getMaxX(), tile.getMaxY());
            if (isCompletelyObscured(tile, intersectingTiles)) {
                completelyObscuredTiles.add(tile);
            }
        }
        return completelyObscuredTiles;
    }

    /**
     * @return list of tiles that are at least partially visible.
     *         Assumes tiles with lexicographically greater tileId values are drawn on top.
     */
    public List<TileBounds> findVisibleTiles() {
        final List<TileBounds> visibleTiles = new ArrayList<>(tileBoundsList.size());
        List<TileBounds> intersectingTiles;
        for (final TileBounds tile : tileBoundsList) {
            intersectingTiles = findTilesInBox(tile.getMinX(), tile.getMinY(),
                                               tile.getMaxX(), tile.getMaxY());
            if (! isCompletelyObscured(tile, intersectingTiles)) {
                visibleTiles.add(tile);
            }
        }
        return visibleTiles;
    }

    /**
     * @param  neighborTrees         list of trees for all neighboring sections to include in the pairing process.
     *
     * @param  neighborRadiusFactor  applied to max(width, height) of each tile
     *                               to determine radius for locating neighbor tiles.
     *
     * @param  excludeCornerNeighbors if true, exclude neighbor tiles whose center x and y is outside the
     *                               source tile's x and y range respectively.
     *
     * @param  excludeSameLayerNeighbors  if true, exclude neighbor tiles in the same layer (z) as the source tile.
     *
     * @param  excludeSameSectionNeighbors  if true, exclude neighbor tiles with the same sectionId as the source tile.
     *
     * @return set of distinct neighbor pairs between this tree's tiles and the specified neighbor trees' tiles.
     */
    public Set<OrderedCanvasIdPair> getCircleNeighbors(final List<TileBoundsRTree> neighborTrees,
                                                       final double neighborRadiusFactor,
                                                       final boolean excludeCornerNeighbors,
                                                       final boolean excludeSameLayerNeighbors,
                                                       final boolean excludeSameSectionNeighbors) {

        String firstTileId = null;
        if (tileBoundsList.size() > 0) {
            firstTileId = tileBoundsList.get(0).getTileId();
        }

        LOG.debug("getCircleNeighbors: entry, {} tiles with z {}, {} neighborTrees, firstTileId is {}",
                  tileBoundsList.size(), z, neighborTrees.size(), firstTileId);

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

            if (! excludeSameLayerNeighbors) {
                searchResults = findTilesInCircle(circle);

                neighborTileIdPairs.addAll(
                        getDistinctPairs(tileBounds, searchResults, excludeCornerNeighbors, excludeSameSectionNeighbors));
            }

            for (final TileBoundsRTree neighborTree : neighborTrees) {
                searchResults = neighborTree.findTilesInCircle(circle);
                neighborTileIdPairs.addAll(
                        getDistinctPairs(tileBounds, searchResults, excludeCornerNeighbors, excludeSameSectionNeighbors));
            }
        }

        LOG.debug("getCircleNeighbors: exit, returning {} pairs", neighborTileIdPairs.size());

        return neighborTileIdPairs;
    }

    /**
     * @param  tile               core tile for comparison.
     * @param  intersectingTiles  list of tiles that intersect with the core tile.
     *
     * @return true if the core tile is completely obscured by other tiles; otherwise false.
     *         Assumes tiles with lexicographically greater tileId values are drawn on top.
     */
    public static boolean isCompletelyObscured(final TileBounds tile,
                                               final List<TileBounds> intersectingTiles) {
        final String tileId = tile.getTileId();
        final Area tileArea = new Area(new Rectangle2D.Double(tile.getMinX(), tile.getMinY(),
                                                              tile.getDeltaX(), tile.getDeltaY()));

        for (final TileBounds intersectingTile : intersectingTiles) {
            if (tileId.compareTo(intersectingTile.getTileId()) < 0) {
                tileArea.subtract(new Area(
                        new Rectangle2D.Double(
                                intersectingTile.getMinX(), intersectingTile.getMinY(),
                                intersectingTile.getDeltaX(), intersectingTile.getDeltaY())));
            }
        }

        return tileArea.isEmpty();
    }

    /**
     * @return distinct set of pairs of the fromTile with each toTile.
     *         If the fromTile is in the toTiles list, it is ignored (fromTile won't be paired with itself).
     */
    public static Set<OrderedCanvasIdPair> getDistinctPairs(final TileBounds fromTile,
                                                            final List<TileBounds> toTiles,
                                                            final boolean excludeCornerNeighbors,
                                                            final boolean excludeSameSectionNeighbors) {
        final Set<OrderedCanvasIdPair> pairs = new HashSet<>(toTiles.size() * 2);
        final String pTileId = fromTile.getTileId();

        final double fromMinX = fromTile.getMinX();
        final double fromMaxX = fromTile.getMaxX();
        final double fromMinY = fromTile.getMinY();
        final double fromMaxY = fromTile.getMaxY();

        final CanvasId p = new CanvasId(fromTile.getSectionId(), pTileId);
        String qTileId;
        for (final TileBounds toTile : toTiles) {
            qTileId = toTile.getTileId();
            if (! pTileId.equals(qTileId)) {

                if (! excludeSameSectionNeighbors || (! fromTile.getSectionId().equals(toTile.getSectionId()))) {

                    if ((! excludeCornerNeighbors) ||
                        isNeighborCenterInRange(fromMinX, fromMaxX, toTile.getMinX(), toTile.getMaxX()) ||
                        isNeighborCenterInRange(fromMinY, fromMaxY, toTile.getMinY(), toTile.getMaxY())) {

                        pairs.add(new OrderedCanvasIdPair(p, new CanvasId(toTile.getSectionId(), qTileId)));
                    }

                }

            }
        }

        return pairs;
    }

    private static boolean isNeighborCenterInRange(final double min,
                                                   final double max,
                                                   final double neighborMin,
                                                   final double neighborMax) {
        final double neighborCenter = neighborMin + ((neighborMax - neighborMin) / 2);
        return ((neighborCenter >= min) && (neighborCenter <= max));
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

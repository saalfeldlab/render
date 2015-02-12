package org.janelia.alignment.spec;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import mpicbg.models.NoninvertibleModelException;

import org.janelia.alignment.json.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinate data associated with a tile.
 *
 * @author Eric Trautman
 */
public class TileCoordinates implements Serializable {

    private String tileId;
    private Boolean visible;
    private final float[] local;
    private final float[] world;
    private String error;

    public TileCoordinates(final String tileId,
                           final float[] local,
                           final float[] world) {
        this.tileId = tileId;
        this.visible = null;
        this.local = local;
        this.world = world;
        this.error = null;
    }

    public String getTileId() {
        return tileId;
    }

    public void setTileId(String tileId) {
        this.tileId = tileId;
    }

    public boolean isVisible() {
        return ((visible != null) && visible);
    }

    public void setVisible(final Boolean visible) {
        this.visible = visible;
    }

    public float[] getLocal() {
        return local;
    }

    public float[] getWorld() {
        return world;
    }

    public boolean hasError() {
        return (error != null);
    }

    public void setError(final String error) {
        this.error = error;
    }

    public String toJson() {
        return JsonUtils.GSON.toJson(this);
    }

    @Override
    public String toString() {
        return toJson();
    }

    public static TileCoordinates buildLocalInstance(final String tileId,
                                                     final float[] local) {
        return new TileCoordinates(tileId, local, null);
    }

    public static TileCoordinates buildWorldInstance(final String tileId,
                                                     final float[] world) {
        return new TileCoordinates(tileId, null, world);
    }

    /**
     * @param  tileSpecList  list of tiles that contain the specified point
     *                       (order of list is assumed to be the same order used for rendering).
     *
     * @param  x             x coordinate.
     * @param  y             y coordinate.
     *
     * @return a local {@link TileCoordinates} instance with the inverse of the specified world point.
     *
     * @throws IllegalStateException
     *   if the specified point cannot be inverted for any of the specified tiles.
     */
    public static List<TileCoordinates> getLocalCoordinates(
            final List<TileSpec> tileSpecList,
            final float x,
            final float y)
            throws IllegalStateException {


        final List<TileCoordinates> tileCoordinatesList = new ArrayList<>();
        List<String> nonInvertibleTileIds = null;
        float[] local;
        TileCoordinates tileCoordinates;
        for (final TileSpec tileSpec : tileSpecList) {
            try {
                local = tileSpec.getLocalCoordinates(x, y, tileSpec.getMeshCellSize());
                tileCoordinates = buildLocalInstance(tileSpec.getTileId(), local);
                tileCoordinatesList.add(tileCoordinates);
            } catch (final NoninvertibleModelException e) {
                if (nonInvertibleTileIds == null) {
                    nonInvertibleTileIds = new ArrayList<>();
                }
                nonInvertibleTileIds.add(tileSpec.getTileId());
            }
        }

        final int numberOfInvertibleCoordinates = tileCoordinatesList.size();
        if (numberOfInvertibleCoordinates == 0) {
            throw new IllegalStateException("world coordinate (" + x + ", " + y + ") found in tile id(s) " +
                                            nonInvertibleTileIds + " cannot be inverted");
        } else {
            // Tiles are rendered in same order as specified tileSpecList.
            // Consequently for overlapping regions, the last tile will be the visible one
            // since it is rendered after or "on top of" the previous tile(s).
            final TileCoordinates lastTileCoordinates = tileCoordinatesList.get(numberOfInvertibleCoordinates - 1);
            lastTileCoordinates.setVisible(true);
        }

        if (nonInvertibleTileIds != null) {
            LOG.info("getLocalCoordinates: skipped inverse transform of ({}, {}) for non-invertible tile id(s) {}, used tile id {} instead",
                     x, y, nonInvertibleTileIds, tileCoordinatesList.get(0).getTileId());
        }

        return tileCoordinatesList;
    }

    public static TileCoordinates getWorldCoordinates(final TileSpec tileSpec,
                                                      final float x,
                                                      final float y) {
        final float[] world = tileSpec.getWorldCoordinates(x, y);
        return buildWorldInstance(tileSpec.getTileId(), world);
    }

    public static TileCoordinates fromJson(final String json) {
        return JsonUtils.GSON.fromJson(json, TileCoordinates.class);
    }

    private static final Logger LOG = LoggerFactory.getLogger(TileCoordinates.class);
}

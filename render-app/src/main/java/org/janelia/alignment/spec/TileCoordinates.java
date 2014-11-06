package org.janelia.alignment.spec;

import mpicbg.models.NoninvertibleModelException;
import org.janelia.alignment.json.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Coordinate data associated with a tile.
 *
 * @author Eric Trautman
 */
public class TileCoordinates {

    private String tileId;
    private float[] local;
    private float[] world;
    private String error;

    public TileCoordinates(String tileId,
                           float[] local,
                           float[] world) {
        this.tileId = tileId;
        this.local = local;
        this.world = world;
        this.error = null;
    }

    public String getTileId() {
        return tileId;
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

    public void setError(String error) {
        this.error = error;
    }

    public static TileCoordinates buildLocalInstance(String tileId,
                                                     float[] local) {
        return new TileCoordinates(tileId, local, null);
    }

    public static TileCoordinates buildWorldInstance(String tileId,
                                                     float[] world) {
        return new TileCoordinates(tileId, null, world);
    }

    /**
     * @param  tileSpecList  list of tiles that contain the specified point.
     * @param  x             x coordinate.
     * @param  y             y coordinate.
     *
     * @return a local {@link TileCoordinates} instance with the inverse of the specified world point.
     *
     * @throws IllegalStateException
     *   if the specified point cannot be inverted for any of the specified tiles.
     */
    public static TileCoordinates getLocalCoordinates(List<TileSpec> tileSpecList,
                                                      float x,
                                                      float y)
            throws IllegalStateException {

        TileCoordinates tileCoordinates = null;

        List<String> nonInvertibleTileIds = null;
        float[] local;
        for (TileSpec tileSpec : tileSpecList) {
            try {
                local = tileSpec.getLocalCoordinates(x, y);
                tileCoordinates = buildLocalInstance(tileSpec.getTileId(), local);
                break;
            } catch (NoninvertibleModelException e) {
                if (nonInvertibleTileIds == null) {
                    nonInvertibleTileIds = new ArrayList<String>();
                }
                nonInvertibleTileIds.add(tileSpec.getTileId());
            }
        }

        if (tileCoordinates == null) {
            throw new IllegalStateException("world coordinate (" + x + ", " + y + ") found in tile id(s) " +
                                            nonInvertibleTileIds + " cannot be inverted");
        }

        if (nonInvertibleTileIds != null) {
            LOG.info("getLocalCoordinates: skipped inverse transform of ({}, {}) for non-invertible tile id(s) {}, used tile id {} instead",
                     x, y, nonInvertibleTileIds, tileCoordinates.getTileId());
        }

        return tileCoordinates;
    }

    public static TileCoordinates getWorldCoordinates(TileSpec tileSpec,
                                                      float x,
                                                      float y) {
        final float[] world = tileSpec.getWorldCoordinates(x, y);
        return buildWorldInstance(tileSpec.getTileId(), world);
    }

    public static TileCoordinates fromJson(String json) {
        return JsonUtils.GSON.fromJson(json, TileCoordinates.class);
    }

    private static final Logger LOG = LoggerFactory.getLogger(TileCoordinates.class);
}

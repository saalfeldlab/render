package org.janelia.alignment.spec;

import org.janelia.alignment.json.JsonUtils;

/**
 * Coordinate data associated with a tile.
 *
 * @author Eric Trautman
 */
public class TileCoordinates {

    private String tileId;
    private float[] local;
    private float[] world;

    public TileCoordinates(String tileId,
                           float[] local,
                           float[] world) {
        this.tileId = tileId;
        this.local = local;
        this.world = world;
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

    public static TileCoordinates buildLocalInstance(String tileId,
                                                     float[] local) {
        return new TileCoordinates(tileId, local, null);
    }

    public static TileCoordinates buildWorldInstance(String tileId,
                                                     float[] world) {
        return new TileCoordinates(tileId, null, world);
    }

    public static TileCoordinates getInverseCoordinates(TileSpec tileSpec,
                                                        float x,
                                                        float y) {
        final float[] local = tileSpec.getInverseCoordinates(x, y);
        return buildLocalInstance(tileSpec.getTileId(), local);
    }

    public static TileCoordinates getTransformedCoordinates(TileSpec tileSpec,
                                                            float x,
                                                            float y) {
        final float[] world = tileSpec.getTransformedCoordinates(x, y);
        return buildWorldInstance(tileSpec.getTileId(), world);
    }

    public static TileCoordinates fromJson(String json) {
        return JsonUtils.GSON.fromJson(json, TileCoordinates.class);
    }

}

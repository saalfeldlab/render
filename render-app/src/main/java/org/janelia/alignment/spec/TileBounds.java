package org.janelia.alignment.spec;

import org.janelia.alignment.json.JsonUtils;

/**
 * Spatial data for a tile.
 *
 * @author Eric Trautman
 */
public class TileBounds {

    private String tileId;
    private Double minX;
    private Double minY;
    private Double maxX;
    private Double maxY;

    public TileBounds(String tileId,
                      Double minX,
                      Double minY,
                      Double maxX,
                      Double maxY) {
        this.tileId = tileId;
        this.minX = minX;
        this.minY = minY;
        this.maxX = maxX;
        this.maxY = maxY;
    }

    public String getTileId() {
        return tileId;
    }

    public boolean isBoundingBoxDefined() {
        return ((minX != null) && (minY != null) && (maxX != null) && (maxY != null));
    }

    public static TileBounds fromJson(String json) {
        return JsonUtils.GSON.fromJson(json, TileBounds.class);
    }
}

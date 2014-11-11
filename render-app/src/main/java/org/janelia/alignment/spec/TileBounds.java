package org.janelia.alignment.spec;

import org.janelia.alignment.json.JsonUtils;

/**
 * Spatial data for a tile.
 *
 * @author Eric Trautman
 */
public class TileBounds extends Bounds {

    private String tileId;

    public TileBounds(String tileId,
                      Double minX,
                      Double minY,
                      Double maxX,
                      Double maxY) {
        super(minX, minY, maxX, maxY);
        this.tileId = tileId;
    }

    public String getTileId() {
        return tileId;
    }

    public static TileBounds fromJson(String json) {
        return JsonUtils.GSON.fromJson(json, TileBounds.class);
    }
}

package org.janelia.alignment.spec;

import org.janelia.alignment.json.JsonUtils;

/**
 * Spatial data for a tile.
 *
 * @author Eric Trautman
 */
public class TileBounds extends Bounds {

    private final String tileId;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private TileBounds() {
        super(null, null, null, null);
        this.tileId = null;
    }

    public String getTileId() {
        return tileId;
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    public static TileBounds fromJson(final String json) {
        return JSON_HELPER.fromJson(json);
    }

    private static final JsonUtils.Helper<TileBounds> JSON_HELPER =
            new JsonUtils.Helper<>(TileBounds.class);
}

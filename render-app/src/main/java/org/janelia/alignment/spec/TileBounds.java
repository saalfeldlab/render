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
        this(null, null, null, null, null, null, null);
    }

    public TileBounds(final String tileId,
                      final Double minX,
                      final Double minY,
                      final Double minZ,
                      final Double maxX,
                      final Double maxY,
                      final Double maxZ) {
        super(minX, minY, minZ, maxX, maxY, maxZ);
        this.tileId = tileId;
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

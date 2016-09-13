package org.janelia.alignment.spec;

import org.janelia.alignment.json.JsonUtils;

/**
 * Spatial data for a tile.
 *
 * @author Eric Trautman
 */
public class TileBounds extends Bounds {

    private final String tileId;
    private final String sectionId;
    private final Double z;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private TileBounds() {
        this(null, null, null, null, null, null, null);
    }

    public TileBounds(final String tileId,
                      final String sectionId,
                      final Double z,
                      final Double minX,
                      final Double minY,
                      final Double maxX,
                      final Double maxY) {
        super(minX, minY, null, maxX, maxY, null);
        this.tileId = tileId;
        this.sectionId = sectionId;
        this.z = z;
    }

    public String getTileId() {
        return tileId;
    }

    public String getSectionId() {
        return sectionId;
    }

    public Double getZ() {
        return z;
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

package org.janelia.alignment.spec;

import org.janelia.alignment.json.JsonUtils;

/**
 * A tile's last transform.
 *
 * @author Eric Trautman
 */
public record LastTileTransform(String tileId, TransformSpec lastTransform) {

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private LastTileTransform() {
        this(null, null);
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    public static LastTileTransform fromJson(final String json) {
        return JSON_HELPER.fromJson(json);
    }

    private static final JsonUtils.Helper<LastTileTransform> JSON_HELPER =
            new JsonUtils.Helper<>(LastTileTransform.class);
}

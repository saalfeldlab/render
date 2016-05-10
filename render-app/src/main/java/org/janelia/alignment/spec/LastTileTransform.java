package org.janelia.alignment.spec;

import org.janelia.alignment.json.JsonUtils;

/**
 * A tile's last transform.
 *
 * @author Eric Trautman
 */
public class LastTileTransform {

    private final String tileId;
    private final TransformSpec lastTransform;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private LastTileTransform() {
        this(null, null);
    }

    public LastTileTransform(final String tileId,
                             final TransformSpec lastTransform) {
        this.tileId = tileId;
        this.lastTransform = lastTransform;
    }

    public String getTileId() {
        return tileId;
    }

    public TransformSpec getLastTransform() {
        return lastTransform;
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

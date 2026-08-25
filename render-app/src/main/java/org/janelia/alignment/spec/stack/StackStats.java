package org.janelia.alignment.spec.stack;

import java.io.Serializable;
import java.util.Set;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.Bounds;
import org.jspecify.annotations.NonNull;

/**
 * Derived stats for a stack.
 *
 * @author Eric Trautman
 */
public record StackStats(Bounds stackBounds,
                         Long sectionCount,
                         Long nonIntegralSectionCount,
                         Long tileCount,
                         Long transformCount,
                         Integer minTileWidth,
                         Integer maxTileWidth,
                         Integer minTileHeight,
                         Integer maxTileHeight,
                         Set<String> channelNames)
        implements Serializable {

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private StackStats() {
        this(null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    public @NonNull String toString() {
        return toJson();
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    private static final JsonUtils.Helper<StackStats> JSON_HELPER =
            new JsonUtils.Helper<>(StackStats.class);
}

package org.janelia.alignment.spec.stack;

import java.io.Serializable;
import java.util.Set;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.Bounds;

/**
 * Derived stats for a stack.
 *
 * @author Eric Trautman
 */
public class StackStats
        implements Serializable {

    private final Bounds stackBounds;
    private final Long sectionCount;
    private final Long nonIntegralSectionCount;
    private final Long tileCount;
    private final Long transformCount;
    private final Integer minTileWidth;
    private final Integer maxTileWidth;
    private final Integer minTileHeight;
    private final Integer maxTileHeight;
    private final Set<String> channelNames;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private StackStats() {
        this.stackBounds = null;
        this.sectionCount = null;
        this.nonIntegralSectionCount = null;
        this.tileCount = null;
        this.transformCount = null;
        this.minTileWidth = null;
        this.maxTileWidth = null;
        this.minTileHeight = null;
        this.maxTileHeight = null;
        this.channelNames = null;
    }

    public StackStats(final Bounds stackBounds,
                      final Long sectionCount,
                      final Long nonIntegralSectionCount,
                      final Long tileCount,
                      final Long transformCount,
                      final Integer minTileWidth,
                      final Integer maxTileWidth,
                      final Integer minTileHeight,
                      final Integer maxTileHeight,
                      final Set<String> channelNames) {
        this.stackBounds = stackBounds;
        this.sectionCount = sectionCount;
        this.nonIntegralSectionCount = nonIntegralSectionCount;
        this.tileCount = tileCount;
        this.transformCount = transformCount;
        this.minTileWidth = minTileWidth;
        this.maxTileWidth = maxTileWidth;
        this.minTileHeight = minTileHeight;
        this.maxTileHeight = maxTileHeight;
        this.channelNames = channelNames;
    }

    public Bounds getStackBounds() {
        return stackBounds;
    }

    public Long getSectionCount() {
        return sectionCount;
    }

    public Long getNonIntegralSectionCount() {
        return nonIntegralSectionCount;
    }

    public Long getTileCount() {
        return tileCount;
    }

    public Long getTransformCount() {
        return transformCount;
    }

    public Integer getMinTileWidth() {
        return minTileWidth;
    }

    public Integer getMaxTileWidth() {
        return maxTileWidth;
    }

    public Integer getMinTileHeight() {
        return minTileHeight;
    }

    public Integer getMaxTileHeight() {
        return maxTileHeight;
    }

    public Set<String> getChannelNames() {
        return channelNames;
    }

    @Override
    public String toString() {
        return toJson();
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    private static final JsonUtils.Helper<StackStats> JSON_HELPER =
            new JsonUtils.Helper<>(StackStats.class);
}

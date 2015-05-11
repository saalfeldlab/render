package org.janelia.alignment.spec.stack;

import java.io.Serializable;

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

    public StackStats(Bounds stackBounds,
                      Long sectionCount,
                      Long nonIntegralSectionCount,
                      Long tileCount,
                      Long transformCount,
                      Integer minTileWidth,
                      Integer maxTileWidth,
                      Integer minTileHeight,
                      Integer maxTileHeight) {
        this.stackBounds = stackBounds;
        this.sectionCount = sectionCount;
        this.nonIntegralSectionCount = nonIntegralSectionCount;
        this.tileCount = tileCount;
        this.transformCount = transformCount;
        this.minTileWidth = minTileWidth;
        this.maxTileWidth = maxTileWidth;
        this.minTileHeight = minTileHeight;
        this.maxTileHeight = maxTileHeight;
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

    @Override
    public String toString() {
        return toJson();
    }

    public String toJson() {
        return JsonUtils.GSON.toJson(this);
    }

}

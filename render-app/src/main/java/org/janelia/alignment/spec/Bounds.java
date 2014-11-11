package org.janelia.alignment.spec;

import org.janelia.alignment.json.JsonUtils;

/**
 * Coordinate bounds for arbitrary group of tiles.
 *
 * @author Eric Trautman
 */
public class Bounds {

    private Double minX;
    private Double minY;
    private Double maxX;
    private Double maxY;

    public Bounds() {
    }

    public Bounds(Double minX,
                  Double minY,
                  Double maxX,
                  Double maxY) {
        this.minX = minX;
        this.minY = minY;
        this.maxX = maxX;
        this.maxY = maxY;
    }

    public Double getMinX() {
        return minX;
    }

    public Double getMinY() {
        return minY;
    }

    public Double getMaxX() {
        return maxX;
    }

    public Double getMaxY() {
        return maxY;
    }

    public boolean isBoundingBoxDefined() {
        return ((minX != null) && (minY != null) && (maxX != null) && (maxY != null));
    }

    public static Bounds fromJson(String json) {
        return JsonUtils.GSON.fromJson(json, Bounds.class);
    }
}

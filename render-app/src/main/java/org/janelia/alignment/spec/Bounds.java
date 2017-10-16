package org.janelia.alignment.spec;

import java.io.Serializable;

import org.janelia.alignment.json.JsonUtils;

/**
 * Coordinate bounds for arbitrary group of tiles.
 *
 * @author Eric Trautman
 */
public class Bounds implements Serializable {

    private Double minX;
    private Double minY;
    private Double minZ;
    private Double maxX;
    private Double maxY;
    private Double maxZ;

    public Bounds() {
    }

    public Bounds(final Double minX,
                  final Double minY,
                  final Double maxX,
                  final Double maxY) {
        this(minX, minY, null, maxX, maxY, null);
    }

    public Bounds(final Double minX,
                  final Double minY,
                  final Double minZ,
                  final Double maxX,
                  final Double maxY,
                  final Double maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
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

    public Double getMaxZ() {
        return maxZ;
    }

    public Double getMinZ() {
        return minZ;
    }

    public boolean isBoundingBoxDefined() {
        return ((minX != null) && (minY != null) && (maxX != null) && (maxY != null));
    }

    public Double getDeltaX() {
        return maxX - minX;
    }

    public Double getDeltaY() {
        return maxY - minY;
    }

    public int getRoundedMinX() {
        return getRoundedValue(minX);
    }

    public int getRoundedMinY() {
        return getRoundedValue(minY);
    }

    public int getRoundedDeltaX() {
        return getRoundedValue(getDeltaX());
    }

    public int getRoundedDeltaY() {
        return getRoundedValue(getDeltaY());
    }

    @Override
    public String toString() {
        return toJson();
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    private int getRoundedValue(final Double value) {
        return (int) (value + 0.5);
    }

    public static Bounds fromJson(final String json) {
        return JSON_HELPER.fromJson(json);
    }

    private static final JsonUtils.Helper<Bounds> JSON_HELPER =
            new JsonUtils.Helper<>(Bounds.class);

}

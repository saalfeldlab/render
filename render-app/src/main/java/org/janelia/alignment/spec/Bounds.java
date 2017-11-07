package org.janelia.alignment.spec;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;

import org.janelia.alignment.json.JsonUtils;

/**
 * Coordinate bound ranges.
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

    @JsonIgnore
    public boolean isBoundingBoxDefined() {
        return ((minX != null) && (minY != null) && (maxX != null) && (maxY != null));
    }

    @JsonIgnore
    public Double getDeltaX() {
        return maxX - minX;
    }

    @JsonIgnore
    public Double getDeltaY() {
        return maxY - minY;
    }

    @Override
    public String toString() {
        return toJson();
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    public static Bounds fromJson(final String json) {
        return JSON_HELPER.fromJson(json);
    }

    private static final JsonUtils.Helper<Bounds> JSON_HELPER =
            new JsonUtils.Helper<>(Bounds.class);

}

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

    @SuppressWarnings("UnusedDeclaration")
    public Double getMaxZ() {
        return maxZ;
    }

    public void setMinZ(final Double minZ) {
        this.minZ = minZ;
    }

    @SuppressWarnings("UnusedDeclaration")
    public Double getMinZ() {
        return minZ;
    }

    public void setMaxZ(final Double maxZ) {
        this.maxZ = maxZ;
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

    @Override
    public String toString() {
        return "{minX: " + minX +
               ", minY: " + minY +
               ", minZ: " + minZ +
               ", maxX: " + maxX +
               ", maxY: " + maxY +
               ", maxZ: " + maxZ +
               '}';
    }

    public static Bounds fromJson(final String json) {
        return JsonUtils.GSON.fromJson(json, Bounds.class);
    }
}

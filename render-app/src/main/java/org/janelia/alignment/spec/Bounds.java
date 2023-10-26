package org.janelia.alignment.spec;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.awt.Rectangle;
import java.io.Serializable;
import java.util.Objects;

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
    public double getDeltaX() {
        return maxX - minX;
    }

    @JsonIgnore
    public double getDeltaY() {
        return maxY - minY;
    }

    @JsonIgnore
    public double getDeltaZ() {
        return ((maxZ == null) || (minZ == null)) ? 0 : maxZ - minZ;
    }

    @JsonIgnore
    public int getX() {
        return minX.intValue();
    }

    @JsonIgnore
    public int getY() {
        return minY.intValue();
    }

    @JsonIgnore
    public int getWidth() {
        return (int) Math.ceil(getDeltaX());
    }

    @JsonIgnore
    public int getHeight() {
        return (int) Math.ceil(getDeltaY());
    }

    @JsonIgnore
    public double getCenterX() {
        return minX + (getDeltaX() / 2.0);
    }

    @JsonIgnore
    public double getCenterY() {
        return minY + (getDeltaY() / 2.0);
    }

    @Override
    public String toString() {
        return String.format("[[%9.1f, %9.1f, %9.1f], [%9.1f, %9.1f, %9.1f]]",
                             minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final Bounds bounds = (Bounds) o;

        if (!minX.equals(bounds.minX)) {
            return false;
        }
        if (!minY.equals(bounds.minY)) {
            return false;
        }
        if (! Objects.equals(minZ, bounds.minZ)) {
            return false;
        }
        if (!maxX.equals(bounds.maxX)) {
            return false;
        }
        if (!maxY.equals(bounds.maxY)) {
            return false;
        }
        return Objects.equals(maxZ, bounds.maxZ);
    }

    @Override
    public int hashCode() {
        int result = minX.hashCode();
        result = 31 * result + minY.hashCode();
        result = 31 * result + (minZ != null ? minZ.hashCode() : 0);
        result = 31 * result + maxX.hashCode();
        result = 31 * result + maxY.hashCode();
        result = 31 * result + (maxZ != null ? maxZ.hashCode() : 0);
        return result;
    }

    public Rectangle toRectangle() {
        return new Rectangle(minX.intValue(), minY.intValue(), getWidth(), getHeight());
    }

    /**
     * @return true if these bounds contain the specified bounds after converting components
     *         (e.g. minX, maxX, minY, ...) to integral values.
     *         Components with null values always contain (or are contained).
     */
    public boolean containsInt(final Bounds bounds) {
        return containsInt(this.minZ, bounds.minZ, true) &&
               containsInt(this.maxZ, bounds.maxZ, false) &&
               containsInt(this.minY, bounds.minY, true) &&
               containsInt(this.maxY, bounds.maxY, false) &&
               containsInt(this.minX, bounds.minX, true) &&
               containsInt(this.maxX, bounds.maxX, false);
    }

    public Bounds union(final Bounds that) {
        return new Bounds(union(this.minX, that.minX, true),
                          union(this.minY, that.minY, true),
                          union(this.minZ, that.minZ, true),
                          union(this.maxX, that.maxX, false),
                          union(this.maxY, that.maxY, false),
                          union(this.maxZ, that.maxZ, false));
    }

    public Bounds withMinZ(final Double minZ) {
        return new Bounds(this.minX, this.minY, minZ, this.maxX, this.maxY, this.maxZ);
    }

    public Bounds withMaxZ(final Double maxZ) {
        return new Bounds(this.minX, this.minY, this.minZ, this.maxX, this.maxY, maxZ);
    }

    public Bounds withZ(final Double z) {
        return new Bounds(this.minX, this.minY, z, this.maxX, this.maxY, z);
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    public static Bounds fromJson(final String json) {
        return JSON_HELPER.fromJson(json);
    }

    private static final JsonUtils.Helper<Bounds> JSON_HELPER =
            new JsonUtils.Helper<>(Bounds.class);

    private static Double union(final Double a,
                                final Double b,
                                final boolean isMin) {
        final Double value;
        if (a == null) {
            value = b;
        } else if (b == null) {
            value = a;
        } else if (isMin) {
            value = Math.min(a, b);
        } else {
            value = Math.max(a, b);
        }
        return value;
    }

    private static boolean containsInt(final Double a,
                                       final Double b,
                                       final boolean isMin) {
        final boolean result;
        if ((a == null) || (b == null)) {
            result = true;
        } else if (isMin) {
            result = a.intValue() <= b.intValue();
        } else {
            result = a.intValue() >= b.intValue();
        }
        return result;
    }
}

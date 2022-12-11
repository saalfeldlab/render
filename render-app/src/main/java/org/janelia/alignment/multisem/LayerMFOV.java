package org.janelia.alignment.multisem;

import java.io.Serializable;

import org.janelia.alignment.json.JsonUtils;

/**
 * Identifies an MFOV in a specific z-layer.
 *
 * @author Eric Trautman
 */
public class LayerMFOV
        implements Comparable<LayerMFOV>, Serializable {

    private final double z;
    private final String name;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private LayerMFOV() {
        this(0.0, "");
    }

    public LayerMFOV(final double z,
                     final String name) {
        this.z = z;
        this.name = name;
    }

    @Override
    public boolean equals(final Object that) {
        if (this == that) {
            return true;
        }
        if (that == null || getClass() != that.getClass()) {
            return false;
        }
        final LayerMFOV layerMfov = (LayerMFOV) that;
        return Double.compare(this.z, layerMfov.z) == 0 && this.name.equals(layerMfov.name);
    }

    @Override
    public int hashCode() {
        int result;
        final long temp;
        temp = Double.doubleToLongBits(z);
        result = (int) (temp ^ (temp >>> 32));
        result = 31 * result + name.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "z_" + z + "_mfov_" + name;
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    private static final JsonUtils.Helper<LayerMFOV> JSON_HELPER =
            new JsonUtils.Helper<>(LayerMFOV.class);

    @Override
    public int compareTo(final LayerMFOV that) {
        int result = Double.compare(this.z, that.z);
        if (result == 0) {
            result = this.name.compareTo(that.name);
        }
        return result;
    }
}

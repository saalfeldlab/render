package org.janelia.alignment.spec;

import java.io.IOException;
import java.io.Reader;
import java.util.Arrays;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;

/**
 * Spatial data for a tile.
 *
 * @author Eric Trautman
 */
public class TileBounds extends Bounds {

    private final String tileId;
    private final String sectionId;
    private final Double z;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private TileBounds() {
        this(null, null, null, null, null, null, null);
    }

    public TileBounds(final String tileId,
                      final String sectionId,
                      final Double z,
                      final Double minX,
                      final Double minY,
                      final Double maxX,
                      final Double maxY) {
        super(minX, minY, z, maxX, maxY, z);
        this.tileId = tileId;
        this.sectionId = sectionId;
        this.z = z;
    }

    public String getTileId() {
        return tileId;
    }

    public String getSectionId() {
        return sectionId;
    }

    public Double getZ() {
        return z;
    }

    @Override
    public TileBounds withXYShift(final Double shiftX,
                                   final Double shiftY) {
        final Bounds shiftedBounds = super.withXYShift(shiftX, shiftY);
        return new TileBounds(tileId, sectionId, z,
                              shiftedBounds.getMinX(), shiftedBounds.getMinY(),
                              shiftedBounds.getMaxX(), shiftedBounds.getMaxY());
    }

    @Override
    public String toString() {
        return tileId + ": " + super.toString();
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    public static TileBounds fromJson(final String json) {
        return JSON_HELPER.fromJson(json);
    }

    public static List<TileBounds> fromJsonArray(final Reader json)
            throws IllegalArgumentException {
        try {
            return Arrays.asList(JsonUtils.MAPPER.readValue(json, TileBounds[].class));
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static final JsonUtils.Helper<TileBounds> JSON_HELPER =
            new JsonUtils.Helper<>(TileBounds.class);
}

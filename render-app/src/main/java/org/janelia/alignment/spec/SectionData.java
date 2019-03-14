package org.janelia.alignment.spec;

import java.beans.Transient;
import java.io.Serializable;
import java.util.Comparator;

import org.janelia.alignment.json.JsonUtils;

/**
 * Maps a section identifier to its z value.
 *
 * @author Eric Trautman
 */
public class SectionData
        implements Serializable {

    private final String sectionId;
    private final Double z;
    private Long tileCount;
    private Double minX;
    private Double maxX;
    private Double minY;
    private Double maxY;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private SectionData() {
        this(null, null, null, null, null, null, null);
    }

    public SectionData(final TileBounds tileBounds) {
        this(tileBounds.getSectionId(),
             tileBounds.getZ(),
             1L,
             tileBounds.getMinX(),
             tileBounds.getMaxX(),
             tileBounds.getMinY(),
             tileBounds.getMaxY());
    }

    public SectionData(final String sectionId,
                       final Double z,
                       final Long tileCount,
                       final Double minX,
                       final Double maxX,
                       final Double minY,
                       final Double maxY) {
        this.sectionId = sectionId;
        this.z = z;
        this.tileCount = tileCount;
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
    }

    public void addTileBounds(final TileBounds tileBounds) {
        this.tileCount++;
        this.minX = Math.min(this.minX, tileBounds.getMinX());
        this.minY = Math.min(this.minY, tileBounds.getMinY());
        this.maxX = Math.max(this.maxX, tileBounds.getMaxX());
        this.maxY = Math.max(this.maxY, tileBounds.getMaxY());
    }

    public String getSectionId() {
        return sectionId;
    }

    public Double getZ() {
        return z;
    }

    public Long getTileCount() {
        return tileCount;
    }

    public Double getMaxX() {
        return maxX;
    }

    public Double getMaxY() {
        return maxY;
    }

    public Double getMinX() {
        return minX;
    }

    public Double getMinY() {
        return minY;
    }

    @Transient
    public int getWidth() {
        return (int) (maxX - minX + 0.5);
    }

    @Transient
    public int getHeight() {
        return (int) (maxY - minY + 0.5);
    }

    public Bounds toBounds() {
        return new Bounds(minX, minY, z, maxX, maxY, z);
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    @Override
    public String toString() {
        return this.toJson();
    }

    public static final Comparator<SectionData> Z_COMPARATOR = Comparator.comparing(o -> o.z);

    private static final JsonUtils.Helper<SectionData> JSON_HELPER =
            new JsonUtils.Helper<>(SectionData.class);

}

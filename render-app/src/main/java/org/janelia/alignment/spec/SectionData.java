package org.janelia.alignment.spec;

import java.io.Serializable;

/**
 * Maps a section identifier to its z value.
 *
 * @author Eric Trautman
 */
public class SectionData
        implements Serializable {

    private final String sectionId;
    private final Double z;
    private final Long tileCount;
    private final Double minX;
    private final Double maxX;
    private final Double minY;
    private final Double maxY;


    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private SectionData() {
        this(null, null, null, null, null, null, null);
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
}

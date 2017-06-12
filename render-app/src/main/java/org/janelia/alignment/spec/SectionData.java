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

    @Transient
    public int getWidth() {
        return (int) (maxX - minX + 0.5);
    }

    @Transient
    public int getHeight() {
        return (int) (maxY - minY + 0.5);
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    @Override
    public String toString() {
        return this.toJson();
    }

    public static final Comparator<SectionData> Z_COMPARATOR = new Comparator<SectionData>() {
        @Override
        public int compare(final SectionData o1,
                           final SectionData o2) {
            return o1.z.compareTo(o2.z);
        }
    };

    private static final JsonUtils.Helper<SectionData> JSON_HELPER =
            new JsonUtils.Helper<>(SectionData.class);

}

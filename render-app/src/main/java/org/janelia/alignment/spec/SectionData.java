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

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private SectionData() {
        this(null, null, null);
    }

    public SectionData(final String sectionId,
                       final Double z,
                       final Long tileCount) {
        this.sectionId = sectionId;
        this.z = z;
        this.tileCount = tileCount;
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
}

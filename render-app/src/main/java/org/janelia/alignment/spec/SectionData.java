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

    public SectionData(final String sectionId,
                       final Double z) {
        this.sectionId = sectionId;
        this.z = z;
    }

    public String getSectionId() {
        return sectionId;
    }

    public Double getZ() {
        return z;
    }
}

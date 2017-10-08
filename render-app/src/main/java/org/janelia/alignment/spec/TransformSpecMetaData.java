package org.janelia.alignment.spec;

import java.io.Serializable;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Any information about a {@link TransformSpec} that is NOT directly needed for rendering
 * (not directly needed for implementation of the transformation).
 *
 * @author Eric Trautman
 */
public class TransformSpecMetaData implements Serializable {

    /** The default label for a lens correction transform. */
    public static final String LENS_CORRECTION_LABEL = "lens";

    private Set<String> labels;

    public TransformSpecMetaData() {
    }

    public boolean hasLabel(final String label) {
        return (labels != null) && labels.contains(label);
    }

    public void addLabel(final String label) {
        if (labels == null) {
            labels = new LinkedHashSet<>();
        }
        labels.add(label);
    }

    public void removeLabel(final String label) {
        if (labels != null) {
            labels.remove(label);
        }
    }

    /**
     * Merges this meta data with the specified parent meta data.
     *
     * @param  parentMetaData  meta data to merge.
     */
    public void merge(final TransformSpecMetaData parentMetaData) {
        if (parentMetaData.labels != null) {
            if (labels == null) {
                labels = new LinkedHashSet<>();
            }
            labels.addAll(parentMetaData.labels);
        }
    }

}

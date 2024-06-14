package org.janelia.alignment.spec.transfer;

import com.fasterxml.jackson.annotation.JsonGetter;

/**
 * Root paths for cluster data.
 *
 * @author Eric Trautman
 */
public class ClusterRootPaths {

    private final String rawDat;
    private final String rawH5;
    private final String alignH5;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private ClusterRootPaths() {
        this(null, null, null);
    }

    public ClusterRootPaths(final String rawDat,
                            final String rawH5,
                            final String alignH5) {
        this.rawDat = rawDat;
        this.rawH5 = rawH5;
        this.alignH5 = alignH5;
    }

    @JsonGetter(value = "raw_dat")
    public String getRawDat() {
        return rawDat;
    }

    @JsonGetter(value = "raw_h5")
    public String getRawH5() {
        return rawH5;
    }

    @JsonGetter(value = "align_h5")
    public String getAlignH5() {
        return alignH5;
    }
}

package org.janelia.alignment.match.parameters;

import com.beust.jcommander.Parameter;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;

/**
 * Parameters for clipping rendered canvases during feature extraction.
 *
 * @author Eric Trautman
 */
public class FeatureRenderClipParameters
        implements Serializable {

    @Parameter(
            names = "--clipWidth",
            description = "Number of full scale pixels to include in rendered clips of LEFT/RIGHT oriented montage tiles"
    )
    public Integer clipWidth;

    @Parameter(
            names = "--clipHeight",
            description = "Number of full scale pixels to include in rendered clips of TOP/BOTTOM oriented montage tiles"
    )
    public Integer clipHeight;

    @JsonIgnore
    public boolean hasValue() {
        return (clipWidth != null) || (clipHeight != null);
    }
}

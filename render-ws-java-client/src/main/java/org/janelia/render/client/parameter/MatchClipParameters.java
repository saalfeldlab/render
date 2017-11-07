package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;

import java.io.Serializable;

/**
 * Parameters for clipping rendered canvases during point match derivation.
 *
 * @author Eric Trautman
 */
public class MatchClipParameters implements Serializable {

    @Parameter(
            names = "--clipWidth",
            description = "Number of full scale pixels to include in rendered clips of LEFT/RIGHT oriented montage tiles",
            required = false)
    public Integer clipWidth;

    @Parameter(
            names = "--clipHeight",
            description = "Number of full scale pixels to include in rendered clips of TOP/BOTTOM oriented montage tiles",
            required = false)
    public Integer clipHeight;

}

package org.janelia.render.client.parameters;

import com.beust.jcommander.Parameter;

import java.io.Serializable;

/**
 * Client parameters for clipping rendered canvases for point match derivation.
 *
 * @author Eric Trautman
 */
public class MatchClipParameters implements Serializable {

    @Parameter(
            names = "--clipWidth",
            description = "Number of full scale pixels to include in rendered clips of LEFT/RIGHT oriented montage tiles",
            required = false,
            order = 20)
    public Integer clipWidth;

    @Parameter(
            names = "--clipHeight",
            description = "Number of full scale pixels to include in rendered clips of TOP/BOTTOM oriented montage tiles",
            required = false,
            order = 21)
    public Integer clipHeight;

}

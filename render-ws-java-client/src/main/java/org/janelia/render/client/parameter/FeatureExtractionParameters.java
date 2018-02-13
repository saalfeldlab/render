package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;

import java.io.Serializable;

/**
 * Parameters for feature extraction.
 *
 * @author Eric Trautman
 */
public class FeatureExtractionParameters
        implements Serializable {

    @Parameter(
            names = "--SIFTfdSize",
            description = "SIFT feature descriptor size: how many samples per row and column",
            required = false)
    public Integer fdSize = 8;

    @Parameter(
            names = "--SIFTminScale",
            description = "SIFT minimum scale: minSize * minScale < size < maxSize * maxScale",
            required = false)
    public Double minScale = 0.5;

    @Parameter(
            names = "--SIFTmaxScale",
            description = "SIFT maximum scale: minSize * minScale < size < maxSize * maxScale",
            required = false)
    public Double maxScale = 0.85;

    @Parameter(
            names = "--SIFTsteps",
            description = "SIFT steps per scale octave",
            required = false)
    public Integer steps = 3;

}

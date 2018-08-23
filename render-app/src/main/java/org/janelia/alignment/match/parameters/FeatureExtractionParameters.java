package org.janelia.alignment.match.parameters;

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
            description = "SIFT feature descriptor size: how many samples per row and column"
    )
    public Integer fdSize = 8;

    @Parameter(
            names = "--SIFTminScale",
            description = "SIFT minimum scale: minSize * minScale < size < maxSize * maxScale"
    )
    public Double minScale = 0.5;

    @Parameter(
            names = "--SIFTmaxScale",
            description = "SIFT maximum scale: minSize * minScale < size < maxSize * maxScale"
    )
    public Double maxScale = 0.85;

    @Parameter(
            names = "--SIFTsteps",
            description = "SIFT steps per scale octave"
    )
    public Integer steps = 3;

}

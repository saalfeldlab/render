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

    public FeatureExtractionParameters() {
        setDefaults();
    }

    @Parameter(
            names = "--SIFTfdSize",
            description = "SIFT feature descriptor size: how many samples per row and column"
    )
    public Integer fdSize;

    @Parameter(
            names = "--SIFTminScale",
            description = "SIFT minimum scale: minSize * minScale < size < maxSize * maxScale"
    )
    public Double minScale;

    @Parameter(
            names = "--SIFTmaxScale",
            description = "SIFT maximum scale: minSize * minScale < size < maxSize * maxScale"
    )
    public Double maxScale;

    @Parameter(
            names = "--SIFTsteps",
            description = "SIFT steps per scale octave"
    )
    public Integer steps;

    void setDefaults() {

        if (fdSize == null) {
            fdSize = 8;
        }
        if (minScale == null) {
            minScale = 0.5;
        }
        if (maxScale == null) {
            maxScale = 0.85;
        }
        if (steps == null) {
            steps = 3;
        }
    }

}

package org.janelia.alignment.match.parameters;

import com.beust.jcommander.Parameter;

import java.io.Serializable;

import mpicbg.stitching.StitchingParameters;

/**
 * Parameters for cross correlation matching.
 */
public class CrossCorrelationParameters
        implements Serializable {

    public CrossCorrelationParameters() {
        setDefaults();
    }

    @Parameter(
            names = "--ccFullScaleSampleSize",
            description = "Full scale pixel height or width for each sample. " +
                          "Combined with clip size to determine sample area."
    )
    public Integer fullScaleSampleSize;

    @Parameter(
            names = "--ccFullScaleStepSize",
            description = "Full scale pixels to offset each sample from the previous sample."
    )
    public Integer fullScaleStepSize;

    @Parameter(
            names = "--ccMinResultThreshold",
            description = "Minimum correlation value for feature candidates."
    )
    public Double minResultThreshold;

    // TODO: ask SP what other stitching parameters should be exposed as command line options

    @Parameter(
            names = "--ccCheckPeaks",
            description = "Number of peaks to check during phase correlation."
    )
    public Integer checkPeaks;

    void setDefaults() {

        if (checkPeaks == null) {
            checkPeaks = 50;
        }

    }

    public int getScaledSampleSize(final double renderScale) {
        return Math.max(10, (int) Math.round(fullScaleSampleSize * renderScale));
    }

    public int getScaledStepSize(final double renderScale) {
        return Math.max(1, (int) Math.round(fullScaleStepSize * renderScale));
    }

    public StitchingParameters toStitchingParameters() {

        final StitchingParameters params = new StitchingParameters();

        // TODO: move some of these to dynamic parameters after talking with SP

        // static parameters
        params.dimensionality = 2;
        params.fusionMethod = 0;
        params.fusedName = "";
        params.addTilesAsRois = false;
        params.computeOverlap = true;
        params.subpixelAccuracy = true;
        params.ignoreZeroValuesFusion = false;
        params.downSample = false;
        params.displayFusion = false;
        params.invertX = false;
        params.invertY = false;
        params.ignoreZStage = false;
        params.xOffset = 0.0;
        params.yOffset = 0.0;
        params.zOffset = 0.0;

        // dynamic parameters
        params.checkPeaks = checkPeaks;

        return params;
    }

}

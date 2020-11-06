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
                          "Combined with clip size to determine sample area.",
            required = true
    )
    public Integer fullScaleSampleSize;

    @Parameter(
            names = "--ccFullScaleStepSize",
            description = "Full scale pixels to offset each sample from the previous sample.",
            required = true
    )
    public Integer fullScaleStepSize;

    @Parameter(
            names = "--ccMinResultThreshold",
            description = "Minimum correlation value for feature candidates.",
            required = true
    )
    public Double minResultThreshold;

    @Parameter(
            names = "--ccCheckPeaks",
            description = "Number of peaks to check during phase correlation."
    )
    public Integer checkPeaks;

    @Parameter(
            names = "--ccSubpixelAccuracy",
            description = "Indicates whether subpixel accuracy should be used for correlation.",
            arity = 1
    )
    public Boolean subpixelAccuracy;

    void setDefaults() {

        if (checkPeaks == null) {
            checkPeaks = 50;
        }

        if (subpixelAccuracy == null) {
            subpixelAccuracy = true;
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

        // static parameters
        params.dimensionality = 2;
        params.fusionMethod = 0;
        params.fusedName = "";
        params.addTilesAsRois = false;
        params.computeOverlap = true;
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
        params.subpixelAccuracy = subpixelAccuracy;

        return params;
    }

}

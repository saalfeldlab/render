package org.janelia.alignment.match.parameters;

import com.beust.jcommander.Parameter;

import java.io.Serializable;

import mpicbg.spim.segmentation.InteractiveDoG;

import plugin.DescriptorParameters;

/**
 * Parameters for geometric descriptor peak extraction and peak filtering.
 */
public class GeometricDescriptorParameters
        implements Serializable {

    public enum LocalizationFitType {

        NONE(1),
        THREE_D_QUADRATIC(2),
        GAUSSIAN_MASK_LOCALIZATION(3);

        private final int code;

        LocalizationFitType(final int code) {
            this.code = code;
        }

    }

    public GeometricDescriptorParameters() {
        setDefaults();
    }

    // TODO: get SP's help writing good accurate descriptions for each parameter

    @Parameter(
            names = "--gdNumberOfNeighbors",
            description = "...",
            required = true
    )
    public Integer numberOfNeighbors;

    @Parameter(
            names = "--gdRedundancy",
            description = "...",
            required = true
    )
    public Integer redundancy;

    @Parameter(
            names = "--gdSignificance",
            description = "...",
            required = true
    )
    public Double significance;

    @Parameter(
            names = "--gdSigma",
            description = "...",
            required = true
    )
    public Double sigma;

    @Parameter(
            names = "--gdThreshold",
            description = "...",
            required = true
    )
    public Double threshold;

    @Parameter(
            names = "--gdLocalization",
            description = "..."
    )
    public LocalizationFitType localization;

    @Parameter(
            names = "--gdLookForMinima",
            description = "...",
            arity = 0
    )
    public boolean lookForMinima = false;

    @Parameter(
            names = "--gdLookForMaxima",
            description = "...",
            arity = 0
    )
    public boolean lookForMaxima = false;

    @Parameter(
            names = "--gdFullScaleBlockRadius",
            description = "..."
    )
    public Double fullScaleBlockRadius;

    @Parameter(
            names = "--gdFullScaleNonMaxSuppressionRadius",
            description = "..."
    )
    public Double fullScaleNonMaxSuppressionRadius;

    private void setDefaults() {

        if (numberOfNeighbors == null) {
            numberOfNeighbors = 3;
        }
        if (redundancy == null) {
            redundancy = 1;
        }
        if (significance == null) {
            significance = 2.0;
        }
        if (sigma == null) {
            sigma = 2.04;
        }
        if (threshold == null) {
            threshold = 0.008;
        }
        if (localization == null) {
            localization = LocalizationFitType.NONE;
        }

    }

    public DescriptorParameters toDescriptorParameters() {
        final DescriptorParameters dp = new DescriptorParameters();

        // static parameters
        dp.dimensionality = 2;
        dp.similarOrientation = true;
        dp.channel1 = 0;
        dp.channel2 = 0;

        // TODO: make sure roi parameters are not relevant ...

        // dynamic parameters
        dp.numNeighbors = numberOfNeighbors;
        dp.redundancy = redundancy;
        dp.significance = significance;
        dp.sigma1 = sigma;
        dp.sigma2 = InteractiveDoG.computeSigma2(sigma.floatValue(), InteractiveDoG.standardSensitivity );
        dp.threshold = threshold;
        dp.localization = localization.code;
        dp.lookForMinima = lookForMinima;
        dp.lookForMaxima = lookForMaxima;
        
        return dp;
    }
}

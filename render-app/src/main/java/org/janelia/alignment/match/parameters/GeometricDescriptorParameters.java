package org.janelia.alignment.match.parameters;

import com.beust.jcommander.Parameter;

import java.io.Serializable;
import java.util.Objects;

import mpicbg.spim.segmentation.InteractiveDoG;

import plugin.DescriptorParameters;

/**
 * Parameters for geometric descriptor peak extraction and peak filtering.
 */
public class GeometricDescriptorParameters
        implements Serializable {

    public enum LocalizationFitType {

        NONE(0),
        THREE_D_QUADRATIC(1);
        // GAUSSIAN_MASK_LOCALIZATION(2);  // not currently supported

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
            description = "Number of neighbors to include when looking for peak"
    )
    public Integer numberOfNeighbors;

    @Parameter(
            names = "--gdRedundancy",
            description = "Redundancy for peak evaluation"
    )
    public Integer redundancy;

    @Parameter(
            names = "--gdSignificance",
            description = "Factor for identifying corresponding descriptors"
    )
    public Double significance;

    @Parameter(
            names = "--gdSigma",
            description = "Factor for Gaussian folding"
    )
    public Double sigma;

    @Parameter(
            names = "--gdThreshold",
            description = "Minimum peak value threshold"
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
            names = "--gdSimilarOrientation",
            description = "...",
            arity = 1
    )
    public boolean similarOrientation = true;

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

    @Parameter(
            names = "--gdStoredMatchWeight",
            description = "..."
    )
    public Double gdStoredMatchWeight;

    void setDefaults() {

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
            localization = LocalizationFitType.THREE_D_QUADRATIC;
        }

    }

    public DescriptorParameters toDescriptorParameters() {
        final DescriptorParameters dp = new DescriptorParameters();

        // static parameters
        dp.dimensionality = 2;
        dp.channel1 = 0;
        dp.channel2 = 0;
        dp.silent = true;

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
        dp.similarOrientation = similarOrientation;

        return dp;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final GeometricDescriptorParameters that = (GeometricDescriptorParameters) o;

        return (lookForMinima == that.lookForMinima) &&
               (lookForMaxima == that.lookForMaxima) &&
               (similarOrientation == that.similarOrientation) &&
               Objects.equals(numberOfNeighbors, that.numberOfNeighbors) &&
               Objects.equals(redundancy, that.redundancy) &&
               Objects.equals(significance, that.significance) &&
               Objects.equals(sigma, that.sigma) &&
               Objects.equals(threshold, that.threshold) &&
               (localization == that.localization) &&
               Objects.equals(fullScaleBlockRadius, that.fullScaleBlockRadius) &&
               Objects.equals(fullScaleNonMaxSuppressionRadius, that.fullScaleNonMaxSuppressionRadius) &&
               Objects.equals(gdStoredMatchWeight, that.gdStoredMatchWeight);
    }

    @Override
    public int hashCode() {
        int result = numberOfNeighbors != null ? numberOfNeighbors.hashCode() : 0;
        result = 31 * result + (redundancy != null ? redundancy.hashCode() : 0);
        result = 31 * result + (significance != null ? significance.hashCode() : 0);
        result = 31 * result + (sigma != null ? sigma.hashCode() : 0);
        result = 31 * result + (threshold != null ? threshold.hashCode() : 0);
        result = 31 * result + (localization != null ? localization.hashCode() : 0);
        result = 31 * result + (lookForMinima ? 1 : 0);
        result = 31 * result + (lookForMaxima ? 1 : 0);
        result = 31 * result + (similarOrientation ? 1 : 0);
        result = 31 * result + (fullScaleBlockRadius != null ? fullScaleBlockRadius.hashCode() : 0);
        result = 31 * result +
                 (fullScaleNonMaxSuppressionRadius != null ? fullScaleNonMaxSuppressionRadius.hashCode() : 0);
        result = 31 * result + (gdStoredMatchWeight != null ? gdStoredMatchWeight.hashCode() : 0);
        return result;
    }
}

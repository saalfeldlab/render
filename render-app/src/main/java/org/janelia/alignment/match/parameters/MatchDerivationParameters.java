package org.janelia.alignment.match.parameters;

import com.beust.jcommander.Parameter;

import java.io.Serializable;

import org.janelia.alignment.match.MatchFilter.FilterType;
import org.janelia.alignment.match.ModelType;

/**
 * Parameters for point match derivation.
 *
 * @author Eric Trautman
 */
public class MatchDerivationParameters implements Serializable {

    public MatchDerivationParameters() {
        setDefaults();
    }

    public MatchDerivationParameters(final float rod,
                                     final ModelType modelType,
                                     final int iterations,
                                     final float maxEpsilon,
                                     final float minInlierRatio,
                                     final int minNumInliers,
                                     final double maxTrust,
                                     final Integer maxNumInliers,
                                     final FilterType filterType) {
        this.matchRod = rod;
        this.matchModelType = modelType;
        this.matchRegularizerModelType = null;
        this.matchInterpolatedModelLambda = null;
        this.matchIterations = iterations;
        this.matchMaxEpsilon = maxEpsilon;
        this.matchMinInlierRatio = minInlierRatio;
        this.matchMinNumInliers = minNumInliers;
        this.matchMaxTrust = maxTrust;
        this.matchMaxNumInliers = maxNumInliers;
        this.matchFilter = filterType;
    }

    @Parameter(
            names = "--matchRod",
            description = "Ratio of distances for matches"
    )
    public Float matchRod;

    @Parameter(
            names = "--matchModelType",
            description = "Type of model for match filtering"
    )
    public ModelType matchModelType;

    @Parameter(
            names = "--matchRegularizerModelType",
            description = "Type of regularizer model for interpolated match filtering (omit for standard filtering)"
    )
    public ModelType matchRegularizerModelType;

    @Parameter(
            names = "--matchInterpolatedModelLambda",
            description = "Lambda for interpolated match filtering (omit for standard filtering)"
    )
    public Double matchInterpolatedModelLambda;

    @Parameter(
            names = "--matchIterations",
            description = "Match filter iterations"
    )
    public Integer matchIterations;

    /** Kept for legacy code compatibility.  Use --matchMaxEpsilonFullScale instead. */
    @Parameter(
            names = "--matchMaxEpsilon",
            description = "Minimal allowed transfer error for match filtering in render scale pixels"
    )
    @Deprecated
    private Float matchMaxEpsilon;

    @Deprecated
    public void setMatchMaxEpsilon(final Float matchMaxEpsilon) {
        this.matchMaxEpsilon = matchMaxEpsilon;
    }

    @Parameter(
            names = "--matchMaxEpsilonFullScale",
            description = "Minimal allowed transfer error for match filtering in full scale pixels.  " +
                          "If specified, will override --matchMaxEpsilon value."
    )
    public Float matchMaxEpsilonFullScale;

    public Float getMatchMaxEpsilonForRenderScale(final Double renderScale) {
        return matchMaxEpsilonFullScale != null ?
               matchMaxEpsilonFullScale * renderScale.floatValue() : matchMaxEpsilon;
    }

    @Parameter(
            names = "--matchMinInlierRatio",
            description = "Minimal ratio of inliers to candidates for match filtering"
    )
    public Float matchMinInlierRatio;

    @Parameter(
            names = "--matchMinNumInliers",
            description = "Minimal absolute number of inliers for match filtering"
    )
    public Integer matchMinNumInliers;

    @Parameter(
            names = "--matchMaxNumInliers",
            description = "Maximum number of inliers for match filtering"
    )
    public Integer matchMaxNumInliers;

    @Parameter(
            names = "--matchMaxTrust",
            description = "Reject match candidates with a cost larger than maxTrust * median cost"
    )
    public Double matchMaxTrust;

    @Parameter(
            names = "--matchFilter",
            description = "Identifies if and how matches should be filtered"
    )
    public FilterType matchFilter = FilterType.SINGLE_SET;

    @Parameter(
            names = "--matchFullScaleCoverageRadius",
            description = "Full scale radius to use for coverage analysis"
    )
    public Double matchFullScaleCoverageRadius;

    @Parameter(
            names = "--matchMinCoveragePercentage",
            description = "Minimum covered pixel percentage for storage.  " +
                          "Omit parameter to ignore coverage."
    )
    public Double matchMinCoveragePercentage;

    public void validateAndSetDefaults(final String context) {
        this.setDefaults();

        if (this.matchRegularizerModelType == null) {
            if (this.matchInterpolatedModelLambda != null) {
                throw new IllegalArgumentException(
                        context +
                        " matchRegularizerModelType must be defined since matchInterpolatedModelLambda is defined");
            }
        } else if (this.matchInterpolatedModelLambda == null) {
                throw new IllegalArgumentException(
                        context +
                        " matchInterpolatedModelLambda must be defined since matchRegularizerModelType is defined");
        }

    }

    private void setDefaults() {
        if (matchRod == null) {
            matchRod = 0.92f;
        }
        if (matchModelType == null) {
            matchModelType = ModelType.AFFINE;
        }
        if (matchIterations == null) {
            matchIterations = 1000;
        }
        if (matchMaxEpsilon == null) {
            matchMaxEpsilon = 20.0f;
        }
        if (matchMinInlierRatio == null) {
            matchMinInlierRatio = 0.0f;
        }
        if (matchMinNumInliers == null) {
            matchMinNumInliers = 4;
        }
        if (matchMaxTrust == null) {
            matchMaxTrust = 3.0;
        }
        if (matchFullScaleCoverageRadius == null) {
            matchFullScaleCoverageRadius = 300.0;
        }
    }

}

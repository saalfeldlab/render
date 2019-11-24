package org.janelia.alignment.match.parameters;

import com.beust.jcommander.Parameter;

import java.io.Serializable;

import org.janelia.alignment.match.MatchFilter.FilterType;
import org.janelia.alignment.match.ModelType;

/**
 * Copy of point match derivation parameters (see {@link MatchDerivationParameters}) with "gd" prefixed parameter names.
 * Could not find better way to handle reusing match parameters for SIFT and GD but with different names.
 *
 * @author Eric Trautman
 */
public class GeometricCommandLineMatchDerivationParameters
        implements Serializable {

    GeometricCommandLineMatchDerivationParameters() {
    }

    @Parameter(
            names = "--gdMatchModelType",
            description = "Type of model for match filtering"
    )
    public ModelType matchModelType;

    @Parameter(
            names = "--gdMatchRegularizerModelType",
            description = "Type of regularizer model for interpolated match filtering (omit for standard filtering)"
    )
    public ModelType matchRegularizerModelType;

    @Parameter(
            names = "--gdMatchInterpolatedModelLambda",
            description = "Lambda for interpolated match filtering (omit for standard filtering)"
    )
    public Double matchInterpolatedModelLambda;

    @Parameter(
            names = "--gdMatchIterations",
            description = "Match filter iterations"
    )
    public Integer matchIterations;

    @Parameter(
            names = "--gdMatchMaxEpsilon",
            description = "Minimal allowed transfer error for match filtering"
    )
    public Float matchMaxEpsilon;

    @Parameter(
            names = "--gdMatchMinInlierRatio",
            description = "Minimal ratio of inliers to candidates for match filtering"
    )
    public Float matchMinInlierRatio;

    @Parameter(
            names = "--gdMatchMinNumInliers",
            description = "Minimal absolute number of inliers for match filtering"
    )
    public Integer matchMinNumInliers;

    @Parameter(
            names = "--gdMatchMaxNumInliers",
            description = "Maximum number of inliers for match filtering"
    )
    public Integer matchMaxNumInliers;

    @Parameter(
            names = "--gdMatchMaxTrust",
            description = "Reject match candidates with a cost larger than maxTrust * median cost"
    )
    public Double matchMaxTrust;

    @Parameter(
            names = "--gdMatchFilter",
            description = "Identifies if and how matches should be filtered"
    )
    public FilterType matchFilter;

    @Parameter(
            names = "--gdMatchFullScaleCoverageRadius",
            description = "Full scale radius to use for coverage analysis"
    )
    public Double matchFullScaleCoverageRadius;

    @Parameter(
            names = "--gdMatchMinCoveragePercentage",
            description = "Minimum covered pixel percentage for storage.  " +
                          "Omit parameter to ignore coverage."
    )
    public Double matchMinCoveragePercentage;

    void applyToMatchDerivationParameters(final MatchDerivationParameters matchDerivationParameters) {
        if (matchModelType != null) {
            matchDerivationParameters.matchModelType = matchModelType;
        }
        if (matchRegularizerModelType != null) {
            matchDerivationParameters.matchRegularizerModelType = matchRegularizerModelType;
        }
        if (matchInterpolatedModelLambda != null) {
            matchDerivationParameters.matchInterpolatedModelLambda = matchInterpolatedModelLambda;
        }
        if (matchIterations != null) {
            matchDerivationParameters.matchIterations = matchIterations;
        }
        if (matchMaxEpsilon != null) {
            matchDerivationParameters.matchMaxEpsilon = matchMaxEpsilon;
        }
        if (matchMinInlierRatio != null) {
            matchDerivationParameters.matchMinInlierRatio = matchMinInlierRatio;
        }
        if (matchMinNumInliers != null) {
            matchDerivationParameters.matchMinNumInliers = matchMinNumInliers;
        }
        if (matchMaxNumInliers != null) {
            matchDerivationParameters.matchMaxNumInliers = matchMaxNumInliers;
        }
        if (matchMaxTrust != null) {
            matchDerivationParameters.matchMaxTrust = matchMaxTrust;
        }
        if (matchFilter != null) {
            matchDerivationParameters.matchFilter = matchFilter;
        }
        if (matchFullScaleCoverageRadius != null) {
            matchDerivationParameters.matchFullScaleCoverageRadius = matchFullScaleCoverageRadius;
        }
        if (matchMinCoveragePercentage != null) {
            matchDerivationParameters.matchMinCoveragePercentage = matchMinCoveragePercentage;
        }
    }

}

package org.janelia.alignment.match.parameters;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;

import static org.janelia.alignment.match.parameters.FeatureAndMatchParameters.validateMatchParametersAndSetDefaults;

/**
 * Geometric descriptor peak and match derivation parameters for a match trial.
 */
public class GeometricDescriptorAndMatchFilterParameters
        implements Serializable {

    @Parameter(
            names = "--gdEnabled",
            description = "Indicates that Geometric Descriptor matching should be performed",
            arity = 0
    )
    public boolean gdEnabled = false;

    @Parameter(
            names = "--gdRenderScale",
            description = "Rendered canvas scale for Geometric Descriptor matching"
    )
    public Double renderScale;

    @Parameter(
            names = "--gdRenderWithFilter",
            description = "Indicates whether rendered canvases for Geometric Descriptor matching should use the default intensity correction filter",
            arity = 1
    )
    public Boolean renderWithFilter;

    @Parameter(
            names = "--gdRenderFilterListName",
            description = "Rendered canvas scale for Geometric Descriptor matching"
    )
    public String renderFilterListName;

    @Parameter(
            names = { "--gdMaxPeakCacheGb" },
            description = "Maximum number of gigabytes of peaks to cache")
    @JsonIgnore
    public Integer maxPeakCacheGb;

    @ParametersDelegate
    public GeometricDescriptorParameters geometricDescriptorParameters = new GeometricDescriptorParameters();

    // cannot use @ParametersDelegate MatchDerivationParameters here because we need to differentiate from SIFT match derivation parameters
    public MatchDerivationParameters matchDerivationParameters = new MatchDerivationParameters();

    @ParametersDelegate
    @JsonIgnore
    @SuppressWarnings("FieldMayBeFinal")
    private GeometricCommandLineMatchDerivationParameters commandLineMatchDerivationParameters =
            new GeometricCommandLineMatchDerivationParameters();

    // TODO: rework these parameters so that they are less confusing
    @Parameter(
            names = "--gdRunGeoRegardlessOfSiftResults",
            description = "Indicates that GD matching should be performed regardless of SIFT results.",
            arity = 0
    )
    public boolean runGeoRegardlessOfSiftResults = false;

    @Parameter(
            names = { "--gdMinCombinedInliers" },
            description = "Minimum number of combined SIFT and GD inliers for storage.  " +
                          "GD matching will only be performed if the number of SIFT matches is less than this number.  " +
                          "Omit parameter to perform GD matching regardless of SIFT results.")
    public Integer minCombinedInliers;

    @Parameter(
            names = { "--gdMinCombinedCoveragePercentage" },
            description = "Minimum combined inlier covered pixel percentage for storage.  " +
                          "GD matching will only be performed if the coverage percentage is less than this number.  " +
                          "Omit parameter to perform GD matching regardless of SIFT results.")
    public Double minCombinedCoveragePercentage;

    @SuppressWarnings("unused")
    public GeometricDescriptorAndMatchFilterParameters() {
    }

    public boolean isGeometricDescriptorMatchingEnabled() {
        return gdEnabled;
    }

    public boolean hasSufficiencyConstraints() {
        return (minCombinedInliers != null) ||
               (minCombinedCoveragePercentage != null);
    }

    public void validateAndSetDefaults() throws IllegalArgumentException {

        if (maxPeakCacheGb == null) {
            maxPeakCacheGb = 2;
        }

        geometricDescriptorParameters.setDefaults();

        commandLineMatchDerivationParameters.applyToMatchDerivationParameters(matchDerivationParameters);
        validateMatchParametersAndSetDefaults("geometric descriptor",
                                              matchDerivationParameters);
    }

}

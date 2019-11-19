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

    @Parameter(
            names = { "--gdMinCombinedInliers" },
            description = "Minimum number of combined SIFT and GD inliers for storage.  " +
                          "GD matching will only be performed if the number of SIFT matches is less than this number.  " +
                          "Omit parameter to perform GD matching regardless of SIFT results.")
    public Integer minCombinedInliers;

    @Parameter(
            names = { "--gdMinCombinedCoverageAreaPercentage" },
            description = "Minimum combined inlier convex hull area percentage for storage.  " +
                          "GD matching will only be performed if the SIFT inlier area is less than this number.  " +
                          "Omit parameter to perform GD matching regardless of SIFT results.")
    public Double minCombinedCoverageAreaPercentage;

    @Parameter(
            names = { "--gdMinCombinedCoverageDistancePercentage" },
            description = "Minimum combined inlier convex hull area percentage for storage.  " +
                          "GD matching will only be performed if the SIFT inlier area is less than this number.  " +
                          "Omit parameter to perform GD matching regardless of SIFT results.")
    public Double minCombinedCoverageDistancePercentage;

    @SuppressWarnings("unused")
    public GeometricDescriptorAndMatchFilterParameters() {
    }

    public boolean hasGeometricDescriptorParameters() {
        return geometricDescriptorParameters != null;
    }

    public boolean hasMatchDerivationParameters() {
        return matchDerivationParameters != null;
    }

    public boolean hasSufficiencyConstraints() {
        return (minCombinedInliers != null) ||
               (minCombinedCoverageAreaPercentage != null) ||
               (minCombinedCoverageDistancePercentage != null);
    }

    public void validateAndSetDefaults() throws IllegalArgumentException {

        if (maxPeakCacheGb == null) {
            maxPeakCacheGb = 2;
        }

        geometricDescriptorParameters.setDefaults();

        if (matchDerivationParameters != null) {
            commandLineMatchDerivationParameters.applyToMatchDerivationParameters(matchDerivationParameters);
            validateMatchParametersAndSetDefaults("geometric descriptor",
                                                  matchDerivationParameters);
        }

    }

}

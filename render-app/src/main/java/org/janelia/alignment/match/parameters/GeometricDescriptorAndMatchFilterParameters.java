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
    private final Double renderScale;

    @Parameter(
            names = "--gdRenderWithFilter",
            description = "Indicates whether rendered canvases for Geometric Descriptor matching should use the default intensity correction filter",
            arity = 1
    )
    private final Boolean renderWithFilter;

    @Parameter(
            names = "--gdRenderScale",
            description = "Rendered canvas scale for Geometric Descriptor matching"
    )
    private final String renderFilterListName;

    @Parameter(
            names = { "--gdMaxPeakCacheGb" },
            description = "Maximum number of gigabytes of peaks to cache")
    @JsonIgnore
    private Integer maxPeakCacheGb;

    @ParametersDelegate
    private final GeometricDescriptorParameters geometricDescriptorParameters;

    // cannot use @ParametersDelegate MatchDerivationParameters here because we need to differentiate from SIFT match derivation parameters
    private final MatchDerivationParameters matchDerivationParameters;

    @ParametersDelegate
    @JsonIgnore
    private final GeometricCommandLineMatchDerivationParameters commandLineMatchDerivationParameters;

    @SuppressWarnings("unused")
    public GeometricDescriptorAndMatchFilterParameters() {
        this(null, null, null, null, null, null);
    }

    public GeometricDescriptorAndMatchFilterParameters(final Double renderScale,
                                                       final Boolean renderWithFilter,
                                                       final String renderFilterListName,
                                                       final Integer maxPeakCacheGb,
                                                       final GeometricDescriptorParameters geometricDescriptorParameters,
                                                       final MatchDerivationParameters matchDerivationParameters) {
        this.renderScale = renderScale;
        this.renderWithFilter = renderWithFilter;
        this.renderFilterListName = renderFilterListName;
        this.maxPeakCacheGb = maxPeakCacheGb;
        this.geometricDescriptorParameters = geometricDescriptorParameters;
        this.matchDerivationParameters = matchDerivationParameters;
        this.commandLineMatchDerivationParameters = new GeometricCommandLineMatchDerivationParameters();
    }

    public boolean hasGeometricDescriptorParameters() {
        return geometricDescriptorParameters != null;
    }

    public GeometricDescriptorParameters getGeometricDescriptorParameters() {
        return geometricDescriptorParameters;
    }

    public boolean hasMatchDerivationParameters() {
        return matchDerivationParameters != null;
    }

    public MatchDerivationParameters getMatchDerivationParameters() {
        return matchDerivationParameters;
    }

    public Double getRenderScale() {
        return renderScale;
    }

    public Boolean getRenderWithFilter() {
        return renderWithFilter;
    }

    public String getRenderFilterListName() {
        return renderFilterListName;
    }

    public Integer getMaxPeakCacheGb() {
        return maxPeakCacheGb;
    }

    public void validateAndSetDefaults() throws IllegalArgumentException {

        if (maxPeakCacheGb == null) {
            maxPeakCacheGb = 2;
        }

        if (geometricDescriptorParameters == null) {
            throw new IllegalArgumentException("geometricDescriptorParameters must be defined");
        } else {
            geometricDescriptorParameters.setDefaults();
        }

        if (matchDerivationParameters != null) {
            commandLineMatchDerivationParameters.applyToMatchDerivationParameters(matchDerivationParameters);
            validateMatchParametersAndSetDefaults("geometric descriptor",
                                                  matchDerivationParameters);
        }

    }

}

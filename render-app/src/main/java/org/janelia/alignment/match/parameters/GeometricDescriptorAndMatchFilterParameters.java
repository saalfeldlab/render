package org.janelia.alignment.match.parameters;

import java.io.Serializable;

import static org.janelia.alignment.match.parameters.FeatureAndMatchParameters.validateMatchParametersAndSetDefaults;

/**
 * Geometric descriptor peak and match derivation parameters for a match trial.
 */
public class GeometricDescriptorAndMatchFilterParameters
        implements Serializable {

    private final GeometricDescriptorParameters geometricDescriptorParameters;
    private final MatchDerivationParameters matchDerivationParameters;
    private final Double renderScale;
    private final Boolean renderWithFilter;
    private final String renderFilterListName;

    @SuppressWarnings("unused")
    public GeometricDescriptorAndMatchFilterParameters() {
        this(null, null, null, null, null);
    }

    public GeometricDescriptorAndMatchFilterParameters(final GeometricDescriptorParameters geometricDescriptorParameters,
                                                       final MatchDerivationParameters matchDerivationParameters,
                                                       final Double renderScale,
                                                       final Boolean renderWithFilter,
                                                       final String renderFilterListName) {
        this.geometricDescriptorParameters = geometricDescriptorParameters;
        this.matchDerivationParameters = matchDerivationParameters;
        this.renderScale = renderScale;
        this.renderWithFilter = renderWithFilter;
        this.renderFilterListName = renderFilterListName;
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

    void validateAndSetDefaults() throws IllegalArgumentException {

        if (geometricDescriptorParameters == null) {
            throw new IllegalArgumentException("geometricDescriptorParameters must be defined");
        } else {
            geometricDescriptorParameters.setDefaults();
        }

        validateMatchParametersAndSetDefaults("geometric descriptor",
                                              matchDerivationParameters);
    }

}

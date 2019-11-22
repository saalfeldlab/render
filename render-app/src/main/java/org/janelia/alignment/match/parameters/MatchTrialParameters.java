package org.janelia.alignment.match.parameters;

import java.io.Reader;
import java.io.Serializable;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;

/**
 * Full set of input parameters for a match trial.
 *
 * @author Eric Trautman
 */
public class MatchTrialParameters implements Serializable {

    private final FeatureAndMatchParameters featureAndMatchParameters;
    private final String pRenderParametersUrl;
    private final String qRenderParametersUrl;
    private final GeometricDescriptorAndMatchFilterParameters geometricDescriptorAndMatchFilterParameters;

    @SuppressWarnings("unused")
    public MatchTrialParameters() {
        this(null, null, null, null);
    }

    @SuppressWarnings("WeakerAccess")
    public MatchTrialParameters(final FeatureAndMatchParameters featureAndMatchParameters,
                                final String pRenderParametersUrl,
                                final String qRenderParametersUrl,
                                final GeometricDescriptorAndMatchFilterParameters geometricDescriptorAndMatchFilterParameters) {
        this.featureAndMatchParameters = featureAndMatchParameters;
        this.pRenderParametersUrl = pRenderParametersUrl;
        this.qRenderParametersUrl = qRenderParametersUrl;
        this.geometricDescriptorAndMatchFilterParameters = geometricDescriptorAndMatchFilterParameters;
    }

    public FeatureAndMatchParameters getFeatureAndMatchParameters() {
        return featureAndMatchParameters;
    }

    public String getpRenderParametersUrl() {
        return pRenderParametersUrl;
    }

    public String getqRenderParametersUrl() {
        return qRenderParametersUrl;
    }

    public boolean hasGeometricDescriptorAndMatchFilterParameters() {
        return geometricDescriptorAndMatchFilterParameters != null;
    }

    public GeometricDescriptorAndMatchFilterParameters getGeometricDescriptorAndMatchFilterParameters() {
        return geometricDescriptorAndMatchFilterParameters;
    }

    public void validateAndSetDefaults() throws IllegalArgumentException {

        if (featureAndMatchParameters == null) {
            throw new IllegalArgumentException("featureAndMatchParameters are not defined");
        } else {
            featureAndMatchParameters.validateAndSetDefaults();
        }

        if (pRenderParametersUrl == null) {
            throw new IllegalArgumentException("pRenderParametersUrl is not defined");
        }

        if (qRenderParametersUrl == null) {
            throw new IllegalArgumentException("qRenderParametersUrl is not defined");
        }

        if (geometricDescriptorAndMatchFilterParameters != null) {
            geometricDescriptorAndMatchFilterParameters.validateAndSetDefaults();
        }
    }

    public static MatchTrialParameters fromJson(final Reader json) {
        return JSON_HELPER.fromJson(json);
    }

    public static List<MatchTrialParameters> fromJsonArray(final Reader json) {
        return JSON_HELPER.fromJsonArray(json);
    }

    private static final JsonUtils.Helper<MatchTrialParameters> JSON_HELPER =
            new JsonUtils.Helper<>(MatchTrialParameters.class);

}

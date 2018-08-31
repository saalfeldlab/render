package org.janelia.alignment.match.parameters;

import java.io.Serializable;

/**
 * Full set of input parameters for a match trial.
 *
 * @author Eric Trautman
 */
public class MatchTrialParameters implements Serializable {

    private final FeatureAndMatchParameters featureAndMatchParameters;
    private final String pRenderParametersUrl;
    private final String qRenderParametersUrl;

    @SuppressWarnings("unused")
    public MatchTrialParameters() {
        this(null, null, null);
    }

    public MatchTrialParameters(final FeatureAndMatchParameters featureAndMatchParameters,
                                final String pRenderParametersUrl,
                                final String qRenderParametersUrl) {
        this.featureAndMatchParameters = featureAndMatchParameters;
        this.pRenderParametersUrl = pRenderParametersUrl;
        this.qRenderParametersUrl = qRenderParametersUrl;
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
    }

}

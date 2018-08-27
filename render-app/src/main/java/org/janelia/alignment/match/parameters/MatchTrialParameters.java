package org.janelia.alignment.match.parameters;

import java.io.Serializable;

import org.janelia.alignment.match.MontageRelativePosition;

/**
 * Full set of input parameters for a match trial.
 *
 * @author Eric Trautman
 */
public class MatchTrialParameters implements Serializable {

    private final FeatureAndMatchParameters featureAndMatchParameters;
    private final Boolean fillWithNoise;
    private final MontageRelativePosition pPosition;
    private final String pUrl;
    private final String qUrl;

    @SuppressWarnings("unused")
    public MatchTrialParameters() {
        this(null, null, null, null, null);
    }

    public MatchTrialParameters(final FeatureAndMatchParameters featureAndMatchParameters,
                                final Boolean fillWithNoise,
                                final MontageRelativePosition pPosition,
                                final String pUrl,
                                final String qUrl) {
        this.featureAndMatchParameters = featureAndMatchParameters;
        this.fillWithNoise = fillWithNoise;
        this.pPosition = pPosition;
        this.pUrl = pUrl;
        this.qUrl = qUrl;
    }

    public FeatureAndMatchParameters getFeatureAndMatchParameters() {
        return featureAndMatchParameters;
    }

    public MontageRelativePosition getpPosition() {
        return pPosition;
    }

    public boolean getFillWithNoise() {
        return (fillWithNoise != null) && fillWithNoise;
    }

    public String getpUrl() {
        return pUrl;
    }

    public String getqUrl() {
        return qUrl;
    }

    public void validateAndSetDefaults() throws IllegalArgumentException {

        if (featureAndMatchParameters == null) {
            throw new IllegalArgumentException("featureAndMatchParameters are not defined");
        } else {
            featureAndMatchParameters.setDefaults();
        }
    }

}

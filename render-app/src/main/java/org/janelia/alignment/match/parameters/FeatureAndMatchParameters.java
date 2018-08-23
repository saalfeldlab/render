package org.janelia.alignment.match.parameters;

/**
 * Feature and match derivation parameters for a match trial.
 *
 * @author Eric Trautman
 */
public class FeatureAndMatchParameters {

    private final FeatureExtractionParameters featureExtractionParameters;
    private final FeatureRenderClipParameters featureRenderClipParameters;
    private final MatchDerivationParameters matchDerivationParameters;

    @SuppressWarnings("unused")
    public FeatureAndMatchParameters() {
        this(null, null, null);
    }

    public FeatureAndMatchParameters(final FeatureExtractionParameters featureExtractionParameters,
                                     final FeatureRenderClipParameters featureRenderClipParameters,
                                     final MatchDerivationParameters matchDerivationParameters) {
        this.featureExtractionParameters = featureExtractionParameters;
        this.featureRenderClipParameters = featureRenderClipParameters;
        this.matchDerivationParameters = matchDerivationParameters;
    }

    public FeatureExtractionParameters getFeatureExtractionParameters() {
        return featureExtractionParameters;
    }

    public FeatureRenderClipParameters getFeatureRenderClipParameters() {
        return featureRenderClipParameters;
    }

    public MatchDerivationParameters getMatchDerivationParameters() {
        return matchDerivationParameters;
    }

}

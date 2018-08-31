package org.janelia.alignment.match.parameters;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;

import org.janelia.alignment.match.MontageRelativePosition;

/**
 * Feature and match derivation parameters for a match trial.
 *
 * @author Eric Trautman
 */
public class FeatureAndMatchParameters implements Serializable {

    private final FeatureExtractionParameters siftFeatureParameters;
    private final MatchDerivationParameters matchDerivationParameters;
    private final MontageRelativePosition pClipPosition;
    private final Integer clipPixels;

    @SuppressWarnings("unused")
    public FeatureAndMatchParameters() {
        this(null, null, null, null);
    }

    public FeatureAndMatchParameters(final FeatureExtractionParameters siftFeatureParameters,
                                     final MatchDerivationParameters matchDerivationParameters,
                                     final MontageRelativePosition pClipPosition,
                                     final Integer clipPixels) {
        this.siftFeatureParameters = siftFeatureParameters;
        this.matchDerivationParameters = matchDerivationParameters;
        this.pClipPosition = pClipPosition;
        this.clipPixels = clipPixels;
    }

    public FeatureExtractionParameters getSiftFeatureParameters() {
        return siftFeatureParameters;
    }

    public MatchDerivationParameters getMatchDerivationParameters() {
        return matchDerivationParameters;
    }

    public MontageRelativePosition getpClipPosition() {
        return pClipPosition;
    }

    @JsonIgnore
    public FeatureRenderClipParameters getClipParameters() {
        final FeatureRenderClipParameters clipParameters = new FeatureRenderClipParameters();
        if (pClipPosition != null) {
            if (MontageRelativePosition.TOP.equals(pClipPosition) ||
                MontageRelativePosition.BOTTOM.equals(pClipPosition)) {
                clipParameters.clipHeight = clipPixels;
            } else {
                clipParameters.clipWidth = clipPixels;
            }
        }
        return clipParameters;
    }

    void validateAndSetDefaults() throws IllegalArgumentException {

        if (siftFeatureParameters == null) {
            throw new IllegalArgumentException("siftFeatureParameters must be defined");
        } else {
            siftFeatureParameters.setDefaults();
        }

        if (matchDerivationParameters == null) {
            throw new IllegalArgumentException("matchDerivationParameters must be defined");
        } else {
            matchDerivationParameters.setDefaults();
        }

        if (pClipPosition != null) {
            if (clipPixels == null) {
                throw new IllegalArgumentException("clipPixels must be defined when pClipPosition is defined");
            }
        } else if (clipPixels != null) {
            throw new IllegalArgumentException("pClipPosition must be defined when clipPixels is defined");
        }
    }

}

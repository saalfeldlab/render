package org.janelia.alignment.match.parameters;

import com.beust.jcommander.Parameter;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;
import java.util.Objects;

/**
 * Parameters for rendering canvases for feature extraction.
 *
 * @author Eric Trautman
 */
public class FeatureRenderParameters
        implements Serializable {

    @Parameter(
            names = "--renderScale",
            description = "Render tiles at this scale"
    )
    public Double renderScale = 1.0;

    @Parameter(
            names = "--renderWithFilter",
            description = "Render tiles using default ad-hoc filter for intensity correction",
            arity = 1)
    public boolean renderWithFilter = true;

    @Parameter(
            names = "--renderFilterListName",
            description = "Apply this filter list to all rendering (overrides renderWithFilter option)"
    )
    public String renderFilterListName;

    @Parameter(
            names = "--renderWithoutMask",
            description = "Render tiles without a mask",
            arity = 1)
    public boolean renderWithoutMask = true;

    @Parameter(
            names = "--renderFullScaleWidth",
            description = "Full scale width for all rendered tiles"
    )
    public Integer renderFullScaleWidth;

    @Parameter(
            names = "--renderFullScaleHeight",
            description = "Full scale height for all rendered tiles"
    )
    public Integer renderFullScaleHeight;

    @Parameter(
            names = "--fillWithNoise",
            description = "This deprecated option is left here to prevent legacy scripts from breaking, but it is ignored.  " +
                          "Features in masked areas are now excluded, so it is no longer necessary to fill canvases with noise.",
            arity = 1)
    @JsonIgnore
    public Boolean deprecatedFillWithNoise;

    @JsonIgnore
    public FeatureRenderParameters copy(final Double renderScaleForCopy,
                                        final boolean renderWithFilterForCopy,
                                        final String renderFilterListNameForCopy) {
        final FeatureRenderParameters copiedParameters = new FeatureRenderParameters();
        copiedParameters.renderScale = renderScaleForCopy;
        copiedParameters.renderWithFilter = renderWithFilterForCopy;
        copiedParameters.renderFilterListName = renderFilterListNameForCopy;
        copiedParameters.renderWithoutMask = this.renderWithoutMask;
        copiedParameters.renderFullScaleWidth = this.renderFullScaleWidth;
        copiedParameters.renderFullScaleHeight = this.renderFullScaleHeight;
        copiedParameters.deprecatedFillWithNoise = this.deprecatedFillWithNoise;
        return copiedParameters;
    }

    public boolean matchesExceptForScale(final FeatureRenderParameters that) {
        return this.renderWithFilter == that.renderWithFilter &&
               Objects.equals(this.renderFilterListName, that.renderFilterListName) &&
               this.renderWithoutMask == that.renderWithoutMask &&
               Objects.equals(this.renderFullScaleWidth, that.renderFullScaleWidth) &&
               Objects.equals(this.renderFullScaleHeight, that.renderFullScaleHeight) &&
               Objects.equals(this.deprecatedFillWithNoise, that.deprecatedFillWithNoise);
    }
}


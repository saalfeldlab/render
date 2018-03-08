package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;

import java.io.Serializable;

/**
 * Parameters for rendering canvases for feature extraction.
 *
 * @author Eric Trautman
 */
public class FeatureRenderParameters
        implements Serializable {

    @Parameter(
            names = "--renderScale",
            description = "Render tiles at this scale",
            required = false)
    public Double renderScale = 1.0;

    @Parameter(
            names = "--renderWithFilter",
            description = "Render tiles using default ad-hoc filter for intensity correction",
            required = false,
            arity = 1)
    public boolean renderWithFilter = true;

    @Parameter(
            names = "--renderFilterListName",
            description = "Apply this filter list to all rendering (overrides renderWithFilter option)",
            required = false)
    public String renderFilterListName;

    @Parameter(
            names = "--renderWithoutMask",
            description = "Render tiles without a mask",
            required = false,
            arity = 1)
    public boolean renderWithoutMask = true;

    @Parameter(
            names = "--renderFullScaleWidth",
            description = "Full scale width for all rendered tiles",
            required = false)
    public Integer renderFullScaleWidth;

    @Parameter(
            names = "--renderFullScaleHeight",
            description = "Full scale height for all rendered tiles",
            required = false)
    public Integer renderFullScaleHeight;

    @Parameter(
            names = "--fillWithNoise",
            description = "Fill each canvas image with noise before rendering to improve point match derivation",
            required = false,
            arity = 1)
    public boolean fillWithNoise = true;

}


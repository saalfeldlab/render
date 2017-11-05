package org.janelia.render.client.parameters;

import com.beust.jcommander.Parameter;

import java.io.Serializable;

/**
 * Client parameters for rendering canvases for point match derivation.
 *
 * @author Eric Trautman
 */
public class MatchRenderParameters implements Serializable {

    @Parameter(
            names = "--renderScale",
            description = "Render tiles at this scale",
            required = false,
            order = 10)
    public Double renderScale = 1.0;

    @Parameter(
            names = "--renderWithFilter",
            description = "Render tiles using a filter for intensity correction",
            required = false,
            arity = 1,
            order = 11)
    public boolean renderWithFilter = true;

    @Parameter(
            names = "--renderWithoutMask",
            description = "Render tiles without a mask",
            required = false,
            arity = 1,
            order = 12)
    public boolean renderWithoutMask = true;

    @Parameter(
            names = "--renderFullScaleWidth",
            description = "Full scale width for all rendered tiles",
            required = false,
            order = 13)
    public Integer renderFullScaleWidth;

    @Parameter(
            names = "--renderFullScaleHeight",
            description = "Full scale height for all rendered tiles",
            required = false,
            order = 14)
    public Integer renderFullScaleHeight;

    @Parameter(
            names = "--fillWithNoise",
            description = "Fill each canvas image with noise before rendering to improve point match derivation",
            required = false,
            arity = 1,
            order = 15)
    public boolean fillWithNoise = true;

}


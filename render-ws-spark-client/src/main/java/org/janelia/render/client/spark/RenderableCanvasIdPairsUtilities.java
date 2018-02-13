package org.janelia.render.client.spark;

import java.net.URISyntaxException;

import javax.annotation.Nonnull;

import org.apache.http.client.utils.URIBuilder;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.janelia.render.client.spark.cache.CachedCanvasData;
import org.janelia.render.client.spark.cache.CanvasDataLoader;
import org.slf4j.LoggerFactory;

/**
 * Utilities for working with {@link RenderableCanvasIdPairs} data.
 *
 * @author Eric Trautman
 */
public class RenderableCanvasIdPairsUtilities {

    /**
     * Looks the generic template from the specified pairs object and returns
     * a template containing specifics for a run.
     *
     * @param  renderParametersUrlTemplate  render URL template for the current run.
     *
     * @param  renderFullScaleWidth     full scale width for all rendered canvases.
     *
     * @param  renderFullScaleHeight    full scale height for all rendered canvases.
     *
     * @param  renderScale              scale to use when rendering canvases for the current run.
     *
     * @param  renderWithFilter         indicates whether intensity filtering should be performed
     *                                  when rendering canvases for the current run.
     *
     * @param  renderWithoutMask        indicates whether masks should be excluded
     *                                  when rendering canvases for the current run.
     *
     * @return render parameters URL template with specifics for the current run.
     *
     * @throws URISyntaxException
     *   if the template cannot be converted into a valid URL.
     */
    public static String getRenderParametersUrlTemplateForRun(final String renderParametersUrlTemplate,
                                                              final Integer renderFullScaleWidth,
                                                              final Integer renderFullScaleHeight,
                                                              final Double renderScale,
                                                              final Boolean renderWithFilter,
                                                              final Boolean renderWithoutMask)
            throws URISyntaxException {



        final CanvasDataLoader canvasDataLoader = new CanvasDataLoader(renderParametersUrlTemplate, null) {
            @Override
            public CachedCanvasData load(@Nonnull final CanvasId key)
                    throws Exception {
                return null;
            }
        };

        final CanvasId canvasId = new CanvasId("canavsGroupIdToken", "canvasIdToken");
        final String populatedTemplate = canvasDataLoader.getRenderParametersUrl(canvasId);

        final URIBuilder uriBuilder = new URIBuilder(populatedTemplate);

        if (renderFullScaleWidth != null) {
            uriBuilder.addParameter("width", renderFullScaleWidth.toString());
        }

        if (renderFullScaleHeight != null) {
            uriBuilder.addParameter("height", renderFullScaleHeight.toString());
        }

        if ((renderScale != null) && (renderScale != 1.0)) {
            uriBuilder.addParameter("scale", renderScale.toString());
        }

        if ((renderWithFilter != null) && renderWithFilter) {
            uriBuilder.addParameter("filter", "true");
        }

        if ((renderWithoutMask != null) && renderWithoutMask) {
            uriBuilder.addParameter("excludeMask", "true");
        }

        // assume all canvases should be normalized for matching
        uriBuilder.addParameter("normalizeForMatching", "true");

        final String populatedRunTemplate = uriBuilder.build().toString();
        String runTemplate = populatedRunTemplate.replaceAll("canavsGroupIdToken",
                                                             RenderableCanvasIdPairs.TEMPLATE_GROUP_ID_TOKEN);
        runTemplate = runTemplate.replaceAll("canvasIdToken",
                                             RenderableCanvasIdPairs.TEMPLATE_ID_TOKEN);

        LOG.info("getRenderParametersUrlTemplateForRun: returning {}", runTemplate);

        return runTemplate;

    }

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(RenderableCanvasIdPairsUtilities.class);

}

package org.janelia.render.client.spark;

import java.io.IOException;
import java.io.Reader;
import java.net.URISyntaxException;
import java.nio.file.FileSystems;
import java.nio.file.Path;

import javax.annotation.Nonnull;

import org.apache.http.client.utils.URIBuilder;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.janelia.render.client.FileUtil;
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
     * @return pairs object loaded from the specified file.
     */
    public static RenderableCanvasIdPairs load(final String dataFile)
            throws IOException, IllegalArgumentException {

        final RenderableCanvasIdPairs renderableCanvasIdPairs;

        final Path path = FileSystems.getDefault().getPath(dataFile).toAbsolutePath();

        LOG.info("load: entry, path={}", path);

        try (final Reader reader = FileUtil.DEFAULT_INSTANCE.getExtensionBasedReader(path.toString())) {
            renderableCanvasIdPairs = RenderableCanvasIdPairs.fromJson(reader);
        }

        LOG.info("load: exit, loaded {} pairs", renderableCanvasIdPairs.size());


        return renderableCanvasIdPairs;
    }

    /**
     * Looks the generic template from the specified pairs object and returns
     * a template containing specifics for a run.
     *
     * @param  renderableCanvasIdPairs  pairs object containing generic template.
     *
     * @param  baseDataUrl              base data URL for the current run.
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
    public static String getRenderParametersUrlTemplateForRun(final RenderableCanvasIdPairs renderableCanvasIdPairs,
                                                              final String baseDataUrl,
                                                              final Integer renderFullScaleWidth,
                                                              final Integer renderFullScaleHeight,
                                                              final Double renderScale,
                                                              final Boolean renderWithFilter,
                                                              final Boolean renderWithoutMask)
            throws URISyntaxException {



        final String template = renderableCanvasIdPairs.getRenderParametersUrlTemplate(baseDataUrl);

        final CanvasDataLoader canvasDataLoader = new CanvasDataLoader(template, null) {
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

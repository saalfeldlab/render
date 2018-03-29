package org.janelia.alignment.match;

import java.io.Serializable;
import java.net.URISyntaxException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.client.utils.URIBuilder;
import org.janelia.alignment.RenderParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.janelia.alignment.match.RenderableCanvasIdPairs.TEMPLATE_GROUP_ID_TOKEN;
import static org.janelia.alignment.match.RenderableCanvasIdPairs.TEMPLATE_ID_TOKEN;

/**
 * This template provides a mechanism ({@link #getRenderParametersUrl(CanvasId)})
 * to derive canvas specific render parameters URLs.
 *
 * @author Eric Trautman
 */
public class CanvasRenderParametersUrlTemplate
        implements Serializable {

    private final String templateString;
    private Integer clipWidth;
    private Integer clipHeight;

    private final boolean containsIdReference;
    private final boolean containsGroupIdReference;

    /**
     * @param  templateString  template for deriving render parameters URL for each canvas.
     */
    public CanvasRenderParametersUrlTemplate(final String templateString) {
        this.templateString = templateString;
        this.containsIdReference = templateString.contains(TEMPLATE_ID_TOKEN);
        this.containsGroupIdReference = templateString.contains(TEMPLATE_GROUP_ID_TOKEN);

        this.clipWidth = null;
        this.clipHeight = null;
    }

    public String getTemplateString() {
        return templateString;
    }

    public Integer getClipWidth() {
        return clipWidth;
    }

    public Integer getClipHeight() {
        return clipHeight;
    }

    /**
     * Save information for clipping rendered canvases based upon their relative montage position.
     *
     * @param  clipWidth                    number of full scale left/right pixels to include in rendered montage clips
     *                                      (or null to if entire canvas width is to be rendered).
     *
     * @param  clipHeight                   number of full scale top/bottom pixels to include in rendered montage clips
     *                                      (or null to if entire canvas height is to be rendered).
     */
    public void setClipInfo(final Integer clipWidth,
                            final Integer clipHeight) {
        this.clipWidth = clipWidth;
        this.clipHeight = clipHeight;
    }

    /**
     * @return the render parameters URL for the specified canvas.
     */
    public String getRenderParametersUrl(final CanvasId canvasId) {

        String url = templateString;

        // TODO: determine whether we need to worry about encoding
        if (containsIdReference) {

            String idValue = canvasId.getId();

            // hack to convert encoded box ids - should revisit this
            final Matcher boxIdMatcher = BOX_ID_PATTERN.matcher(idValue);
            if (boxIdMatcher.matches() && (boxIdMatcher.groupCount() >= 1)) {
                // convert z_1.0_box_12769_7558_13654_18227_0.1               to 12769,7558,13654,18227,0.1
                // convert z_1.0_box_12769_7558_13654_18227_0.1_set_1.0_2.0_3 to 12769,7558,13654,18227,0.1
                idValue = boxIdMatcher.group(1).replace('_', ',');
            }

            final Matcher idMatcher = ID_TOKEN_PATTERN.matcher(url);
            url = idMatcher.replaceAll(idValue);
        }

        if (containsGroupIdReference) {
            final Matcher groupIMatcher = GROUP_ID_TOKEN_PATTERN.matcher(url);
            url = groupIMatcher.replaceAll(canvasId.getGroupId());
        }

        return url;
    }

    public RenderParameters getRenderParameters(final CanvasId canvasId)
            throws IllegalArgumentException {
        return getRenderParameters(canvasId, getRenderParametersUrl(canvasId));
    }

    public RenderParameters getRenderParameters(final CanvasId canvasId,
                                                final String renderParametersUrl)
            throws IllegalArgumentException {

        final RenderParameters renderParameters = RenderParameters.loadFromUrl(renderParametersUrl);

        if ((clipWidth != null) || (clipHeight != null)) {
            // TODO: setting the canvas offsets here is hack-y, probably want a cleaner way
            canvasId.setClipOffsets(renderParameters.getWidth(), renderParameters.getHeight(), clipWidth, clipHeight);
            renderParameters.clipForMontagePair(canvasId, clipWidth, clipHeight);
        }

        return renderParameters;
    }

    /**
     * Converts general template (typically read from from a pairs list file) into
     * a run-specific template with the specified parameters.
     *
     * @param  generalTemplateString    general URL template string.
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
    public static CanvasRenderParametersUrlTemplate getTemplateForRun(final String generalTemplateString,
                                                                      final Integer renderFullScaleWidth,
                                                                      final Integer renderFullScaleHeight,
                                                                      final Double renderScale,
                                                                      final Boolean renderWithFilter,
                                                                      final String renderFilterListName,
                                                                      final Boolean renderWithoutMask)
            throws URISyntaxException {

        final String canvasGroupIdToken = "canvasGroupIdToken";
        final String canvasIdToken = "canvasIdToken";

        final CanvasId canvasId = new CanvasId(canvasGroupIdToken, canvasIdToken);
        final CanvasRenderParametersUrlTemplate generalTemplate = new CanvasRenderParametersUrlTemplate(generalTemplateString);
        final String populatedTemplateString = generalTemplate.getRenderParametersUrl(canvasId);

        final URIBuilder uriBuilder = new URIBuilder(populatedTemplateString);

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

        if (renderFilterListName != null) {
            uriBuilder.addParameter("filterListName", renderFilterListName);
        }

        if ((renderWithoutMask != null) && renderWithoutMask) {
            uriBuilder.addParameter("excludeMask", "true");
        }

        // assume all canvases should be normalized for matching
        uriBuilder.addParameter("normalizeForMatching", "true");

        final String populatedRunTemplate = uriBuilder.build().toString();
        String runTemplate = populatedRunTemplate.replaceAll(canvasGroupIdToken,
                                                             RenderableCanvasIdPairs.TEMPLATE_GROUP_ID_TOKEN);
        runTemplate = runTemplate.replaceAll(canvasIdToken,
                                             RenderableCanvasIdPairs.TEMPLATE_ID_TOKEN);

        LOG.info("getTemplateForRun: returning {}", runTemplate);

        return new CanvasRenderParametersUrlTemplate(runTemplate);
    }

    private static Pattern buildTokenPattern(final String token) {
        return Pattern.compile("\\" + token.substring(0, token.length() - 1) + "\\}");
    }

    private static final Logger LOG = LoggerFactory.getLogger(CanvasRenderParametersUrlTemplate.class);

    private static final Pattern ID_TOKEN_PATTERN = buildTokenPattern(TEMPLATE_ID_TOKEN);
    private static final Pattern GROUP_ID_TOKEN_PATTERN = buildTokenPattern(TEMPLATE_GROUP_ID_TOKEN);
    private static final Pattern BOX_ID_PATTERN = Pattern.compile("z_.*_box_(.*)(_set_.*)?");
}

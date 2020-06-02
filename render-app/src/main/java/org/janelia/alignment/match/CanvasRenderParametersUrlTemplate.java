package org.janelia.alignment.match;

import java.io.Serializable;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.client.utils.URIBuilder;
import org.janelia.alignment.match.parameters.FeatureRenderClipParameters;
import org.janelia.alignment.match.parameters.FeatureRenderParameters;
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
    private final FeatureRenderParameters featureRenderParameters;
    private final FeatureRenderClipParameters featureRenderClipParameters;

    private final boolean containsIdReference;
    private final boolean containsGroupIdReference;

    /**
     * @param  templateString  template for deriving render parameters URL for each canvas.
     */
    public CanvasRenderParametersUrlTemplate(final String templateString,
                                             final FeatureRenderParameters featureRenderParameters,
                                             final FeatureRenderClipParameters featureRenderClipParameters) {
        this.templateString = templateString;
        this.featureRenderParameters = featureRenderParameters;
        this.featureRenderClipParameters = featureRenderClipParameters;

        this.containsIdReference = templateString.contains(TEMPLATE_ID_TOKEN);
        this.containsGroupIdReference = templateString.contains(TEMPLATE_GROUP_ID_TOKEN);
    }

    public String getTemplateString() {
        return templateString;
    }

    public Integer getClipWidth() {
        return featureRenderClipParameters == null ? null : featureRenderClipParameters.clipWidth;
    }

    public Integer getClipHeight() {
        return featureRenderClipParameters == null ? null : featureRenderClipParameters.clipHeight;
    }

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

    public Double getRenderScale() {
        return featureRenderParameters.renderScale;
    }

    private String getTemplateStringWithoutQueryParameters() {
        return templateString.substring(0, templateString.indexOf('?'));
    }

    public boolean matchesExceptForScale(final CanvasRenderParametersUrlTemplate that) {
        return Objects.equals(this.getTemplateStringWithoutQueryParameters(),
                              that.getTemplateStringWithoutQueryParameters()) &&
               this.featureRenderParameters.matchesExceptForScale(that.featureRenderParameters);
    }

    /**
     * Converts general template (typically read from from a pairs list file) into
     * a run-specific template with the specified parameters.
     *
     * @param  generalTemplateString    general URL template string.
     *
     * @param  featureRenderParameters  additional parameters to add to the base template
     *                                  when rendering canvases for the current run.
     *
     * @return render parameters URL template with specifics for the current run.
     *
     * @throws IllegalArgumentException
     *   if the template cannot be converted into a valid URL.
     */
    public static CanvasRenderParametersUrlTemplate getTemplateForRun(final String generalTemplateString,
                                                                      final FeatureRenderParameters featureRenderParameters,
                                                                      final FeatureRenderClipParameters featureRenderClipParameters)
            throws IllegalArgumentException {

        final String canvasGroupIdToken = "canvasGroupIdToken";
        final String canvasIdToken = "canvasIdToken";

        final CanvasId canvasId = new CanvasId(canvasGroupIdToken, canvasIdToken);
        final CanvasRenderParametersUrlTemplate generalTemplate =
                new CanvasRenderParametersUrlTemplate(generalTemplateString,
                                                      featureRenderParameters,
                                                      featureRenderClipParameters);
        final String populatedTemplateString = generalTemplate.getRenderParametersUrl(canvasId);

        final URIBuilder uriBuilder;
        try {
            uriBuilder = new URIBuilder(populatedTemplateString);
        } catch (final URISyntaxException e) {
            throw new IllegalArgumentException("invalid base URL", e);
        }

        if (featureRenderParameters.renderFullScaleWidth != null) {
            uriBuilder.addParameter("width", featureRenderParameters.renderFullScaleWidth.toString());
        }

        if (featureRenderParameters.renderFullScaleHeight != null) {
            uriBuilder.addParameter("height", featureRenderParameters.renderFullScaleHeight.toString());
        }

        if (featureRenderParameters.renderScale != null) {
            uriBuilder.addParameter("scale", featureRenderParameters.renderScale.toString());
        } else {
            uriBuilder.addParameter("scale", "1.0");
        }

        if (featureRenderParameters.renderWithFilter) {
            uriBuilder.addParameter("filter", "true");
        }

        if (featureRenderParameters.renderFilterListName != null) {
            uriBuilder.addParameter("filterListName", featureRenderParameters.renderFilterListName);
        }

        if (featureRenderParameters.renderWithoutMask) {
            uriBuilder.addParameter("excludeMask", "true");
        }

        // assume all canvases should be normalized for matching
        uriBuilder.addParameter("normalizeForMatching", "true");

        final String populatedRunTemplate;
        try {
            populatedRunTemplate = uriBuilder.build().toString();
        } catch (final URISyntaxException e) {
            throw new IllegalArgumentException("invalid parameterized URL", e);
        }

        String runTemplate = populatedRunTemplate.replaceAll(canvasGroupIdToken,
                                                             RenderableCanvasIdPairs.TEMPLATE_GROUP_ID_TOKEN);
        runTemplate = runTemplate.replaceAll(canvasIdToken,
                                             RenderableCanvasIdPairs.TEMPLATE_ID_TOKEN);

        LOG.info("getTemplateForRun: returning {}", runTemplate);

        return new CanvasRenderParametersUrlTemplate(runTemplate, featureRenderParameters, featureRenderClipParameters);
    }

    private static Pattern buildTokenPattern(final String token) {
        return Pattern.compile("\\" + token.substring(0, token.length() - 1) + "\\}");
    }

    private static final Logger LOG = LoggerFactory.getLogger(CanvasRenderParametersUrlTemplate.class);

    private static final Pattern ID_TOKEN_PATTERN = buildTokenPattern(TEMPLATE_ID_TOKEN);
    private static final Pattern GROUP_ID_TOKEN_PATTERN = buildTokenPattern(TEMPLATE_GROUP_ID_TOKEN);
    private static final Pattern BOX_ID_PATTERN = Pattern.compile("z_.*_box_(.*)(_set_.*)?");
}

package org.janelia.render.client.spark.cache;

import com.google.common.cache.CacheLoader;

import java.io.Serializable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.match.CanvasId;

import static org.janelia.alignment.match.RenderableCanvasIdPairs.TEMPLATE_GROUP_ID_TOKEN;
import static org.janelia.alignment.match.RenderableCanvasIdPairs.TEMPLATE_ID_TOKEN;

/**
 * Loader implementations create data that is missing from the cache.
 *
 * This base class provides a mechanism ({@link #getRenderParametersUrl(CanvasId)})
 * to derive canvas specific render parameters URLs needed to build missing data.
 *
 * @author Eric Trautman
 */
public abstract class CanvasDataLoader
        extends CacheLoader<CanvasId, CachedCanvasData> implements Serializable {

    private final String renderParametersUrlTemplate;
    private Integer clipWidth;
    private Integer clipHeight;
    private final Class dataClass;

    private final boolean templateContainsIdReference;
    private final boolean templateContainsGroupIdReference;

    /**
     * @param  renderParametersUrlTemplate  template for deriving render parameters URL for each canvas.
     *
     * @param  dataClass                    class of specific data loader implementation.
     */
    protected CanvasDataLoader(final String renderParametersUrlTemplate,
                               final Class dataClass) {
        this.renderParametersUrlTemplate = renderParametersUrlTemplate;
        this.templateContainsIdReference = renderParametersUrlTemplate.contains(TEMPLATE_ID_TOKEN);
        this.templateContainsGroupIdReference = renderParametersUrlTemplate.contains(TEMPLATE_GROUP_ID_TOKEN);

        this.clipWidth = null;
        this.clipHeight = null;

        this.dataClass = dataClass;
    }

    public Class getDataClass() {
        return dataClass;
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

        String url = renderParametersUrlTemplate;

        // TODO: determine whether we need to worry about encoding
        if (templateContainsIdReference) {

            String idValue = canvasId.getId();

            // hack to convert encoded box ids - should revisit this
            final Matcher boxIdMatcher = BOX_ID_PATTERN.matcher(idValue);
            if (boxIdMatcher.matches() && (boxIdMatcher.groupCount() == 1)) {
                // convert z_1.0_box_12769_7558_13654_18227_0.1 to 12769,7558,13654,18227,0.1
                idValue = boxIdMatcher.group(1).replace('_', ',');
            }

            final Matcher idMatcher = ID_TOKEN_PATTERN.matcher(url);
            url = idMatcher.replaceAll(idValue);
        }

        if (templateContainsGroupIdReference) {
            final Matcher groupIMatcher = GROUP_ID_TOKEN_PATTERN.matcher(url);
            url = groupIMatcher.replaceAll(canvasId.getGroupId());
        }

        return url;
    }

    public RenderParameters getRenderParameters(final CanvasId canvasId)
            throws IllegalArgumentException {

        final RenderParameters renderParameters = RenderParameters.loadFromUrl(getRenderParametersUrl(canvasId));

        if ((clipWidth != null) || (clipHeight != null)) {
            // TODO: setting the canvas offsets here is hack-y, probably want a cleaner way
            canvasId.setClipOffsets(renderParameters.getWidth(), renderParameters.getHeight(), clipWidth, clipHeight);
            renderParameters.clipForMontagePair(canvasId.getClipOffsets(), clipWidth, clipHeight);
        }

        return renderParameters;
    }

    private static Pattern buildTokenPattern(final String token) {
        return Pattern.compile("\\" + token.substring(0, token.length() - 1) + "\\}");
    }

    private static final Pattern ID_TOKEN_PATTERN = buildTokenPattern(TEMPLATE_ID_TOKEN);
    private static final Pattern GROUP_ID_TOKEN_PATTERN = buildTokenPattern(TEMPLATE_GROUP_ID_TOKEN);
    private static final Pattern BOX_ID_PATTERN = Pattern.compile("z_.*_box_(.*)");

}

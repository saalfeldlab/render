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
        this.dataClass = dataClass;
    }

    public Class getDataClass() {
        return dataClass;
    }

    /**
     * @return the render parameters URL for the specified canvas.
     */
    public String getRenderParametersUrl(final CanvasId canvasId) {

        String url = renderParametersUrlTemplate;

        // TODO: determine whether we need to worry about encoding
        if (templateContainsIdReference) {

            final Matcher idMatcher = ID_TOKEN_PATTERN.matcher(url);
            url = idMatcher.replaceAll(canvasId.getId());

            if (templateContainsGroupIdReference) {
                final Matcher groupIMatcher = GROUP_ID_TOKEN_PATTERN.matcher(url);
                url = groupIMatcher.replaceAll(canvasId.getGroupId());
            }

        } else if (templateContainsGroupIdReference) {

            final Matcher groupIMatcher = GROUP_ID_TOKEN_PATTERN.matcher(url);
            url = groupIMatcher.replaceAll(canvasId.getGroupId());

        }

        return url;
    }

    public RenderParameters getRenderParameters(final CanvasId canvasId)
            throws IllegalArgumentException {
        return RenderParameters.loadFromUrl(getRenderParametersUrl(canvasId));
    }

    private static final Pattern ID_TOKEN_PATTERN =
            Pattern.compile("\\" + TEMPLATE_ID_TOKEN.substring(0, TEMPLATE_ID_TOKEN.length() - 1) + "\\}");

    private static final Pattern GROUP_ID_TOKEN_PATTERN =
            Pattern.compile("\\" + TEMPLATE_GROUP_ID_TOKEN.substring(0, TEMPLATE_GROUP_ID_TOKEN.length() - 1) + "\\}");

}

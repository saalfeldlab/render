package org.janelia.alignment.match;

import java.io.Reader;
import java.io.Serializable;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;

/**
 * List of {@link OrderedCanvasIdPair} objects with a common render parameters URL template.
 *
 * @author Eric Trautman
 */
public class RenderableCanvasIdPairs
        implements Serializable {

    /** URL template token for the web service base URL. */
    public static final String TEMPLATE_BASE_DATA_URL_TOKEN = "{baseDataUrl}";

    /** URL template token for the canvas groupId. */
    public static final String TEMPLATE_GROUP_ID_TOKEN = "{groupId}";

    /** URL template token for the canvas id. */
    public static final String TEMPLATE_ID_TOKEN = "{id}";

    /** URL template for rendering canvases. */
    private final String renderParametersUrlTemplate;

    /** Pairs of neighboring canvases for matching. */
    private final List<OrderedCanvasIdPair> neighborPairs;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private RenderableCanvasIdPairs() {
        this(null, null);
    }

    public RenderableCanvasIdPairs(final String renderUrlTemplate,
                                   final List<OrderedCanvasIdPair> neighborPairs) {
        this.renderParametersUrlTemplate = renderUrlTemplate;
        this.neighborPairs = neighborPairs;
    }

    public String getRenderParametersUrlTemplate() {
        return renderParametersUrlTemplate;
    }

    public String getRenderParametersUrlTemplate(final String baseDataUrl) {
        String template = renderParametersUrlTemplate;
        if (renderParametersUrlTemplate.startsWith(TEMPLATE_BASE_DATA_URL_TOKEN)) {
            template = baseDataUrl + renderParametersUrlTemplate.substring(TEMPLATE_BASE_DATA_URL_TOKEN.length());
        }
        return template;
    }

    public int size() {
        return neighborPairs.size();
    }

    public List<OrderedCanvasIdPair> getNeighborPairs() {
        return neighborPairs;
    }

    public static RenderableCanvasIdPairs fromJson(final Reader json) {
        return JSON_HELPER.fromJson(json);
    }

    private static final JsonUtils.Helper<RenderableCanvasIdPairs> JSON_HELPER =
            new JsonUtils.Helper<>(RenderableCanvasIdPairs.class);

}

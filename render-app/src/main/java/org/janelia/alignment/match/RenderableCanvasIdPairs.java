package org.janelia.alignment.match;

import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.util.FileUtil;
import org.slf4j.LoggerFactory;

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

    public void addNeighborPairs(final Collection<OrderedCanvasIdPair> pairs) {
        this.neighborPairs.addAll(pairs);
    }

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


    public static RenderableCanvasIdPairs fromJson(final Reader json) {
        return JSON_HELPER.fromJson(json);
    }

    private static final JsonUtils.Helper<RenderableCanvasIdPairs> JSON_HELPER =
            new JsonUtils.Helper<>(RenderableCanvasIdPairs.class);

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(RenderableCanvasIdPairs.class);

}

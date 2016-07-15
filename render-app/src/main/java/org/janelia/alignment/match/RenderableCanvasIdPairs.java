package org.janelia.alignment.match;

import java.io.Serializable;
import java.util.List;

/**
 * List of {@link OrderedCanvasIdPair} objects with a common render parameters URL template.
 *
 * @author Eric Trautman
 */
public class RenderableCanvasIdPairs
        implements Serializable {

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

    public int size() {
        return neighborPairs.size();
    }

    public List<OrderedCanvasIdPair> getNeighborPairs() {
        return neighborPairs;
    }
}

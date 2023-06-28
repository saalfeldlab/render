package org.janelia.render.client.match;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.render.client.TilePairClient;

/**
 * {@link TilePairClient} that saves pairs to in-memory list rather than writing them to JSON files.
 *
 * @author Eric Trautman
 */
public class InMemoryTilePairClient extends TilePairClient {

    private String renderParametersUrlTemplate;
    private final List<OrderedCanvasIdPair> neighborPairs;

    public InMemoryTilePairClient(final Parameters parameters)
            throws IllegalArgumentException, IOException {
        super(parameters);
        this.renderParametersUrlTemplate = null;
        this.neighborPairs = new ArrayList<>();
    }

    public String getRenderParametersUrlTemplate() {
        return renderParametersUrlTemplate;
    }

    public List<OrderedCanvasIdPair> getNeighborPairs() {
        return neighborPairs;
    }

    @Override
    public void savePairs(final List<OrderedCanvasIdPair> neighborPairs,
                           final String renderParametersUrlTemplate,
                           final String outputFileName) {

        this.renderParametersUrlTemplate = renderParametersUrlTemplate;
        this.neighborPairs.addAll(neighborPairs);
    }

}

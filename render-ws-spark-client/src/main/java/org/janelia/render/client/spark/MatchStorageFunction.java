package org.janelia.render.client.spark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.apache.spark.api.java.function.Function2;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.render.client.RenderDataClient;

/**
 * Spark function for storing the non-empty point matches derived on a partition.
 *
 * @author Eric Trautman
 */
public class MatchStorageFunction implements Function2<Integer, Iterator<CanvasMatches>, Iterator<Integer>> {

    private final String baseDataUrl;
    private final String owner;
    private final String collection;

    public MatchStorageFunction(final String baseDataUrl,
                                final String owner,
                                final String collection) {
        this.baseDataUrl = baseDataUrl;
        this.owner = owner;
        this.collection = collection;
    }

    public String getBaseDataUrl() {
        return baseDataUrl;
    }

    public String getOwner() {
        return owner;
    }

    @Override
    public Iterator<Integer> call(final Integer partitionIndex,
                                  final Iterator<CanvasMatches> matchesIterator)
            throws Exception {

        LogUtilities.setupExecutorLog4j("partition " + partitionIndex);

        final List<CanvasMatches> matchesList = new ArrayList<>();
        CanvasMatches canvasMatches;
        while (matchesIterator.hasNext()) {
            canvasMatches = matchesIterator.next();
            if (canvasMatches.size() > 0) {
                matchesList.add(canvasMatches);
            }
        }

        final RenderDataClient matchStorageClient = new RenderDataClient(baseDataUrl,
                                                                         owner,
                                                                         collection);
        matchStorageClient.saveMatches(matchesList);

        return Collections.singletonList(matchesList.size()).iterator();
    }
}
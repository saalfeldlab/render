package org.janelia.render.client.spark;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.render.client.RenderDataClient;

/**
 * A {@link MatchStorageFunction} that supports storing matches from different pairs
 * to different collections. This allows a heterogeneous batch of pairs to be processed
 * in parallel.
 *
 * @author Eric Trautman
 */
public class MultiCollectionMatchStorageFunction extends MatchStorageFunction {

    private final Map<String, String> pIdToCollectionMap;

    public MultiCollectionMatchStorageFunction(final String baseDataUrl,
                                               final String owner) {
        super(baseDataUrl, owner, null);
        this.pIdToCollectionMap = new HashMap<>();
    }

    public void mapPIdToCollection(final String pId,
                                   final String matchCollectionName) {
        pIdToCollectionMap.put(pId, matchCollectionName);
    }

    @Override
    public Iterator<Integer> call(final Integer partitionIndex,
                                  final Iterator<CanvasMatches> matchesIterator)
            throws Exception {

        LogUtilities.setupExecutorLog4j("partition " + partitionIndex);

        String previousCollection = null;
        String currentCollection;

        final List<CanvasMatches> matchesList = new ArrayList<>();
        int savedMatchCount = 0;
        CanvasMatches canvasMatches;
        while (matchesIterator.hasNext()) {
            canvasMatches = matchesIterator.next();
            if (canvasMatches.size() > 0) {

                currentCollection = pIdToCollectionMap.get(canvasMatches.getOriginalPId());

                if (currentCollection == null) {

                    throw new IllegalStateException("no collection mapped for pId " + canvasMatches.getpId());

                } else if (! currentCollection.equals(previousCollection)) {

                    savedMatchCount += saveMatchesAndClearList(previousCollection, matchesList);
                    previousCollection = currentCollection;
                }

                matchesList.add(canvasMatches);

            }
        }

        savedMatchCount += saveMatchesAndClearList(previousCollection, matchesList);

        return Collections.singletonList(savedMatchCount).iterator();
    }

    private int saveMatchesAndClearList(final String collectionName,
                                        final List<CanvasMatches> matchesList)
            throws IOException {

        int savedMatchCount = 0;
        if (collectionName != null) {
            final RenderDataClient matchStorageClient = new RenderDataClient(getBaseDataUrl(),
                                                                             getOwner(),
                                                                             collectionName);
            matchStorageClient.saveMatches(matchesList);
            savedMatchCount = matchesList.size();
            matchesList.clear();
        }
        return savedMatchCount;
    }
}
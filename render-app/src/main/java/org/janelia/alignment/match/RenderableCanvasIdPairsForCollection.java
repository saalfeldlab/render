package org.janelia.alignment.match;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.spec.stack.StackId;

/**
 * {@link RenderableCanvasIdPairs} coupled with an identifier for the match collection in which they should be stored.
 *
 * @author Eric Trautman
 */
public class RenderableCanvasIdPairsForCollection
        implements Serializable {

    /** Stack identifier for source canvas tiles. */
    private final StackId stackId;

    /** Optional information to identify source batch for pairs (e.g. "11 of 20"). */
    private final String batchInfo;

    /** Renderable pairs for match generation. */
    private final RenderableCanvasIdPairs pairs;

    /** Identifies collection for storage of matches generated from these pairs. */
    private final MatchCollectionId matchCollectionId;

    public RenderableCanvasIdPairsForCollection(final StackId stackId,
                                                final RenderableCanvasIdPairs pairs,
                                                final MatchCollectionId matchCollectionId) {
        this(stackId, null, pairs, matchCollectionId);
    }

    public RenderableCanvasIdPairsForCollection(final StackId stackId,
                                                final String batchInfo,
                                                final RenderableCanvasIdPairs pairs,
                                                final MatchCollectionId matchCollectionId) {
        this.stackId = stackId;
        this.batchInfo = batchInfo;
        this.pairs = pairs;
        this.matchCollectionId = matchCollectionId;
    }

    public RenderableCanvasIdPairs getPairs() {
        return pairs;
    }

    public MatchCollectionId getMatchCollectionId() {
        return matchCollectionId;
    }

    @Override
    public String toString() {
        return "{\"stackId\":\"" + stackId.toDevString() +
               "\", \"batchInfo\":\"" + batchInfo +
               "\", \"matchCollectionId\":\"" + matchCollectionId.toDevString() +
               "\", \"numberOfPairs\": " + pairs.size() + "}";
    }

    public int size() {
        return pairs.size();
    }

    public List<RenderableCanvasIdPairsForCollection> splitPairsIntoBalancedBatches(final int maxPairsPerBatch) {
        final List<RenderableCanvasIdPairsForCollection> batchList = new ArrayList<>();
        if (maxPairsPerBatch >= pairs.size()) {
            batchList.add(this);
        } else {

            int totalNumberOfBatches = pairs.size() / maxPairsPerBatch;
            if ((pairs.size() % maxPairsPerBatch) > 0) {
                totalNumberOfBatches++;
            }

            final int pairsPerBatch = pairs.size() / totalNumberOfBatches;
            final int numberOfBatchesWithExtraPair = pairs.size() % pairsPerBatch;

            int toIndex;
            for (int fromIndex = 0; fromIndex < pairs.size();) {

                final int batchIndex = batchList.size();
                final String batchInfo = (batchIndex + 1) + " of " + totalNumberOfBatches;
                toIndex = fromIndex + pairsPerBatch;
                if (batchIndex < numberOfBatchesWithExtraPair) {
                    toIndex++;
                }

                final RenderableCanvasIdPairs batchPairs =
                        new RenderableCanvasIdPairs(pairs.getRenderParametersUrlTemplate(),
                                                    new ArrayList<>(pairs.getNeighborPairs().subList(fromIndex,
                                                                                                     toIndex)));
                batchList.add(new RenderableCanvasIdPairsForCollection(stackId,
                                                                       batchInfo,
                                                                       batchPairs,
                                                                       matchCollectionId));
                fromIndex = toIndex;
            }

        }

        return batchList;
    }
}

package org.janelia.alignment.multisem;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.spec.stack.StackId;

/**
 * Information about unconnected MFOV pairs in a stack.
 *
 * @author Eric Trautman
 */
public class UnconnectedMFOVPairsForStack
        implements Serializable {

    private final StackId renderStackId;
    private final MatchCollectionId matchCollectionId;
    private final List<OrderedMFOVPair> unconnectedMFOVPairs;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private UnconnectedMFOVPairsForStack() {
        this(null, null);
    }

    public UnconnectedMFOVPairsForStack(final StackId renderStackId,
                                        final MatchCollectionId matchCollectionId) {
        this.renderStackId = renderStackId;
        this.matchCollectionId = matchCollectionId;
        this.unconnectedMFOVPairs = new ArrayList<>();
    }

    public StackId getRenderStackId() {
        return renderStackId;
    }

    public MatchCollectionId getMatchCollectionId() {
        return matchCollectionId;
    }

    public int size() {
        return unconnectedMFOVPairs.size();
    }

    public void addUnconnectedPair(final OrderedMFOVPair unconnectedPair) {
        unconnectedMFOVPairs.add(unconnectedPair);
    }
}

package org.janelia.alignment.match;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utility to organize and consolidate match pair data from consensus sets.
 *
 * @author Eric Trautman
 */
public class ConsensusSetPairs {

    private final Set<String> splitGroupIds;       // "bad" canvases that are surrounded by split layer canvases
    private final Set<String> consistentGroupIds;  // "good" canvases that are not

    private final List<CanvasMatches> derivedPairs;

    /**
     * Basic constructor.
     *
     * @param  multiConsensusPairs  set of all cross layer multi-consensus match pairs.
     */
    @SuppressWarnings("Convert2streamapi")
    public ConsensusSetPairs(final Set<CanvasMatches> multiConsensusPairs) {

        this.splitGroupIds = new HashSet<>();
        this.consistentGroupIds = new HashSet<>();

        sortGroupIds(multiConsensusPairs);
        final Set<CanvasMatches> consolidatedSplitPairs = consolidateSplitCanvases(multiConsensusPairs);
        this.derivedPairs = consolidateConsistentCanvases(consolidatedSplitPairs);
    }

    /**
     * @return list of "consistent" match pairs created by the match redistribution process.
     */
    public List<CanvasMatches> getDerivedPairs() {
        return derivedPairs;
    }

    public boolean hasSplitGroups() {
        return splitGroupIds.size() > 0;
    }

    public Set<String> getSplitGroupIds() {
        return splitGroupIds;
    }

    @Override
    public String toString() {
        return "{ splitGroupIds: " + splitGroupIds +
               ", consistentGroupIds: " + consistentGroupIds +
               ", derivedPairsSize: " + derivedPairs.size() +
               '}';
    }

    private void sortGroupIds(final Set<CanvasMatches> multiConsensusPairs) {

        final Set<String> pGroupIds = new HashSet<>();
        final Set<String> qGroupIds = new HashSet<>();
        for (final CanvasMatches pair : multiConsensusPairs) {
            pGroupIds.add(pair.getpGroupId());
            qGroupIds.add(pair.getqGroupId());
        }

        for (final String pGroupId : pGroupIds) {
            if (qGroupIds.contains(pGroupId)) {
                this.splitGroupIds.add(pGroupId);
            } else {
                this.consistentGroupIds.add(pGroupId);
            }
        }

        //noinspection Convert2streamapi
        for (final String qGroupId : qGroupIds) {
            if (! pGroupIds.contains(qGroupId)) {
                this.consistentGroupIds.add(qGroupId);
            }
        }

        // TODO: need to handle cases with only 2 layers (how do you know which is "bad"?)
        if ((this.splitGroupIds.size() == 0) && (this.consistentGroupIds.size() == 2)) {
            // hack assumes last id is bad
            final List<String> sortedGroupIds = this.consistentGroupIds.stream().sorted().collect(Collectors.toList());
            this.consistentGroupIds.remove(sortedGroupIds.get(1));
            this.splitGroupIds.add(sortedGroupIds.get(1));
        }

        // TODO: need to handle cases when first or last layer of entire stack is split (fold is in the very first or last layer)
    }

    private Set<CanvasMatches> consolidateSplitCanvases(final Set<CanvasMatches> multiConsensusPairs) {

        final Set<CanvasMatches> consolidatedSplitPairs = new HashSet<>(multiConsensusPairs.size());

        for (final String splitGroupId : splitGroupIds) {
            final DerivedMatchGroup derivedMatchGroup = new DerivedMatchGroup(splitGroupId, multiConsensusPairs);
            consolidatedSplitPairs.addAll(derivedMatchGroup.getDerivedPairs());
        }

        return consolidatedSplitPairs;
    }

    private List<CanvasMatches> consolidateConsistentCanvases(final Set<CanvasMatches> multiConsensusPairs) {

        final List<CanvasMatches> consolidatedPairs = new ArrayList<>(multiConsensusPairs.size());

        CanvasMatches consolidatedPair;
        for (final CanvasMatches pair : multiConsensusPairs) {
            if (consistentGroupIds.contains(pair.getpGroupId())) {
                consolidatedPair = new CanvasMatches(pair.getpGroupId(),
                                                     pair.getOriginalPId(),
                                                     pair.getqGroupId(),
                                                     pair.getqId(),
                                                     pair.getMatches());
            } else if (consistentGroupIds.contains(pair.getqGroupId())) {
                consolidatedPair = new CanvasMatches(pair.getpGroupId(),
                                                     pair.getpId(),
                                                     pair.getqGroupId(),
                                                     pair.getOriginalQId(),
                                                     pair.getMatches());
            } else {
                consolidatedPair = pair;
            }
            consolidatedPair.setConsensusSetData(pair.getConsensusSetData());
            consolidatedPairs.add(consolidatedPair);
        }

        return consolidatedPairs;
    }


}

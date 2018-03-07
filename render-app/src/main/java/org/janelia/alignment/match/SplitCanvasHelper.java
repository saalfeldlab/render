package org.janelia.alignment.match;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;

/**
 * Tracks split consensus set canvas information to simplify creation of related tile spec copies.
 *
 * @author Eric Trautman
 */
public class SplitCanvasHelper {

    private final Map<Double, Map<String, Set<String>>> zToSplitCanvasMaps;
    private int canvasCount;

    public SplitCanvasHelper() {
        this.zToSplitCanvasMaps = new HashMap<>();
    }

    /**
     * @return sorted list of distinct tracked z values.
     */
    public List<Double> getSortedZValues() {
        final List<Double> zValues = new ArrayList<>(zToSplitCanvasMaps.keySet());
        Collections.sort(zValues);
        return zValues;
    }

    /**
     * @param  z  desired layer.
     *
     * @return set of original tile identifiers for the specified z.
     */
    public Set<String> getOriginalIdsForZ(final Double z) {
        final Map<String, Set<String>> splitTileIdMap = zToSplitCanvasMaps.get(z);
        return splitTileIdMap.keySet();
    }

    /**
     * @return total number of canvases being tracked.
     */
    public int getCanvasCount() {
        return canvasCount;
    }

    /**
     * Loops through the specified match pairs and adds any consensus set canvas ids to
     * the tracked data managed here.
     *
     * @param  derivedMatchPairs  collection of derived match pairs.
     */
    public void trackSplitCanvases(final Collection<CanvasMatches> derivedMatchPairs) {
        for (final CanvasMatches derivedPair : derivedMatchPairs) {
            final ConsensusSetData setData = derivedPair.getConsensusSetData();
            if (setData != null) {
                trackSplitCanvas(derivedPair.getpGroupId(), setData.getOriginalPId(), derivedPair.getpId());
                trackSplitCanvas(derivedPair.getqGroupId(), setData.getOriginalQId(), derivedPair.getqId());
            }
        }
    }

    /**
     * Creates tile spec copies for all tracked derived tiles and adds them to the specified collection.
     *
     * @param  z              current layer.
     * @param  resolvedTiles  source tile spec collection to which the derived tile specs should be appended.
     */
    public void addDerivedTileSpecsToCollection(final Double z,
                                                final ResolvedTileSpecCollection resolvedTiles) {

        final Map<String, Set<String>> splitTileIdMap = zToSplitCanvasMaps.get(z);
        for (final String originalTileId : splitTileIdMap.keySet()) {
            final TileSpec originalTileSpec = resolvedTiles.getTileSpec(originalTileId);
            for (final String derivedTileId : splitTileIdMap.get(originalTileId)) {
                // TODO: refactor this slow but simple copy method if performance is ever an issue
                final TileSpec derivedTileSpec = TileSpec.fromJson(originalTileSpec.toJson());
                derivedTileSpec.setTileId(derivedTileId);
                resolvedTiles.addTileSpecToCollection(derivedTileSpec);
            }
        }
    }

    private void trackSplitCanvas(final String groupId,
                                  final String originalId,
                                  final String derivedId) {

        if (! originalId.equals(derivedId)) {

            final Double z = Double.parseDouble(groupId);

            Map<String, Set<String>> splitTileIdMap = zToSplitCanvasMaps.get(z);
            if (splitTileIdMap == null) {
                splitTileIdMap = new HashMap<>();
                zToSplitCanvasMaps.put(z, splitTileIdMap);
            }

            Set<String> splitTileIds = splitTileIdMap.get(originalId);
            if (splitTileIds == null) {
                splitTileIds = new HashSet<>();
                splitTileIdMap.put(originalId, splitTileIds);
            }

            splitTileIds.add(derivedId);
            canvasCount++;
        }

    }

}

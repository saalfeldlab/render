package org.janelia.alignment.match;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * List of connected canvas clusters sorted by cluster size (largest to smallest).
 *
 * @author Eric Trautman
 */
public class SortedConnectedCanvasIdClusters
        implements Serializable {

    private final List<Set<CanvasId>> sortedConnectedCanvasIdSets;

    public SortedConnectedCanvasIdClusters(final List<CanvasMatches> matchesList) {

        final Map<CanvasId, Set<CanvasId>> connectionsMap = new HashMap<>();

        Set<CanvasId> pSet;
        Set<CanvasId> qSet;

        for (final CanvasMatches matches : matchesList) {
            final CanvasId pCanvasId = new CanvasId(matches.getpGroupId(), matches.getpId());
            final CanvasId qCanvasId = new CanvasId(matches.getqGroupId(), matches.getqId());

            pSet = connectionsMap.computeIfAbsent(pCanvasId, k -> new HashSet<>());
            pSet.add(qCanvasId);

            qSet = connectionsMap.computeIfAbsent(qCanvasId, k -> new HashSet<>());
            qSet.add(pCanvasId);
        }

        this.sortedConnectedCanvasIdSets = new ArrayList<>();

        boolean needsMerge = false;
        while (connectionsMap.size() > 0) {
            final CanvasId canvasId = connectionsMap.keySet().stream().findFirst().get();
            final Set<CanvasId> connectedTileSet = new HashSet<>();
            final boolean isMaxRecursion = addConnectedCanvases(canvasId,
                                                                connectionsMap,
                                                                connectedTileSet,
                                                                0);
            needsMerge = needsMerge || isMaxRecursion;
            sortedConnectedCanvasIdSets.add(connectedTileSet);
        }

        sortedConnectedCanvasIdSets.sort((s1, s2) -> Integer.compare(s2.size(), s1.size()));

        if (needsMerge) {

            final int sizeBeforeMerge = sortedConnectedCanvasIdSets.size();

            LOG.debug("merging {} connected canvasId sets because max recursion occurred during initial pass",
                      sizeBeforeMerge);

            // remove all but the largest set and then merge them back in ...
            final List<Set<CanvasId>> mergeSets = new ArrayList<>(sizeBeforeMerge);
            for (int i = sizeBeforeMerge - 1; i >= 1; i--) {
                mergeSets.add(sortedConnectedCanvasIdSets.remove(i));
            }
            for (final Set<CanvasId> mergeSet : mergeSets) {
                this.mergeOverlappingClusters(new SortedConnectedCanvasIdClusters(mergeSet));
            }

            sortedConnectedCanvasIdSets.sort((s1, s2) -> Integer.compare(s2.size(), s1.size()));

            LOG.debug("{} connected canvasId sets remain after merge", sortedConnectedCanvasIdSets.size());
        }

    }

    /**
     * Constructs a container with just one (the specified) connected cluster.
     */
    private SortedConnectedCanvasIdClusters(final Set<CanvasId> onlyCluster) {
        this.sortedConnectedCanvasIdSets = new ArrayList<>();
        this.sortedConnectedCanvasIdSets.add(onlyCluster);
    }

    public void mergeOverlappingClusters(final SortedConnectedCanvasIdClusters overlappingClusters) {

        final List<Set<CanvasId>> unmergedClusters = overlappingClusters.sortedConnectedCanvasIdSets;

        // try to merge as many of the overlapping clusters as possible
        for (int clusterIndex = 0; clusterIndex < sortedConnectedCanvasIdSets.size(); clusterIndex++) {

            final Set<CanvasId> existingCluster = sortedConnectedCanvasIdSets.get(clusterIndex);

            if (existingCluster.size() > 0) {

                for (final Iterator<Set<CanvasId>> unmergedClusterIterator = unmergedClusters.iterator();
                     unmergedClusterIterator.hasNext(); ) {

                    final Set<CanvasId> unmergedCluster = unmergedClusterIterator.next();
                    boolean isMerged = false;

                    for (final CanvasId canvasId : unmergedCluster) {
                        if (existingCluster.contains(canvasId)) {
                            existingCluster.addAll(unmergedCluster);
                            unmergedClusterIterator.remove();
                            isMerged = true;
                            break;
                        }
                    }

                    if (isMerged) {

                        // check if merged cluster overlaps with any other existing clusters ...

                        final List<Set<CanvasId>> uncheckedExistingClusters =
                                sortedConnectedCanvasIdSets.subList((clusterIndex + 1),
                                                                    sortedConnectedCanvasIdSets.size());

                        for (final Set<CanvasId> uncheckedExistingCluster : uncheckedExistingClusters) {
                            if (uncheckedExistingCluster.size() > 0) {
                                for (final CanvasId canvasId : unmergedCluster) {
                                    if (uncheckedExistingCluster.contains(canvasId)) {
                                        existingCluster.addAll(uncheckedExistingCluster);
                                        uncheckedExistingCluster.clear();
                                    }
                                }
                            }
                        }

                    }
                }

            }

        }

        sortedConnectedCanvasIdSets.removeIf(s -> s.size() == 0);
        
        // then add any remaining unmerged clusters
        sortedConnectedCanvasIdSets.addAll(unmergedClusters);

        sortedConnectedCanvasIdSets.sort((s1, s2) -> Integer.compare(s2.size(), s1.size()));
    }

    public List<Set<String>> getSortedConnectedGroupIdSets() {
        final List<Set<String>> groupIdSets = new ArrayList<>(sortedConnectedCanvasIdSets.size());
        sortedConnectedCanvasIdSets.forEach(canvasIdSet -> {
            final Set<String> groupIdSet = new HashSet<>(canvasIdSet.size());
            canvasIdSet.forEach(canvasId -> groupIdSet.add(canvasId.getGroupId()));
            groupIdSets.add(groupIdSet);
        });
        return groupIdSets;
    }

    public List<Set<String>> getSortedConnectedTileIdSets() {
        final List<Set<String>> tileIdSets = new ArrayList<>(sortedConnectedCanvasIdSets.size());
        sortedConnectedCanvasIdSets.forEach(canvasIdSet -> {
            final Set<String> tileIdSet = new HashSet<>(canvasIdSet.size());
            canvasIdSet.forEach(canvasId -> tileIdSet.add(canvasId.getId()));
            tileIdSets.add(tileIdSet);
        });
        return tileIdSets;
    }

    public int size() {
        return sortedConnectedCanvasIdSets.size();
    }

    public List<Integer> getClusterSizes() {
        final List<Integer> clusterSizes = new ArrayList<>();
        sortedConnectedCanvasIdSets.forEach(tileIds -> clusterSizes.add(tileIds.size()));
        return clusterSizes;
    }

    @Override
    public String toString() {
        return size() + " clusters with sizes " + getClusterSizes();
    }

    private boolean addConnectedCanvases(final CanvasId canvasId,
                                         final Map<CanvasId, Set<CanvasId>> connectionsMap,
                                         final Set<CanvasId> connectedTileSet,
                                         final int recursionCallCount) {

        boolean isMaxRecursion = false;
        
        if (recursionCallCount >= MAX_RECURSION_COUNT) {

            isMaxRecursion = true;

        } else {

            final boolean isNewConnection = connectedTileSet.add(canvasId);

            final int nextRecursionCallCount = recursionCallCount + 1;

            if (isNewConnection) {

                final Set<CanvasId> connectedCanvasIds = connectionsMap.remove(canvasId);

                if (connectedCanvasIds != null) {
                    for (final CanvasId connectedCanvasId : connectedCanvasIds) {
                        isMaxRecursion = isMaxRecursion || addConnectedCanvases(connectedCanvasId,
                                                                                connectionsMap,
                                                                                connectedTileSet,
                                                                                nextRecursionCallCount);
                    }
                }
            }

        }

        return isMaxRecursion;
    }

    private static final Logger LOG = LoggerFactory.getLogger(SortedConnectedCanvasIdClusters.class);

    /** Arbitrary recursion threshold when traversing connected graph that works well enough for current use cases. */
    private static final int MAX_RECURSION_COUNT = 1000;
}

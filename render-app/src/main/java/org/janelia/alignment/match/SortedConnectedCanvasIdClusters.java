package org.janelia.alignment.match;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

        while (connectionsMap.size() > 0) {
            final CanvasId canvasId = connectionsMap.keySet().stream().findFirst().get();
            final Set<CanvasId> connectedTileSet = new HashSet<>();
            addConnectedCanvases(canvasId, connectionsMap, connectedTileSet);
            sortedConnectedCanvasIdSets.add(connectedTileSet);
        }

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

    private void addConnectedCanvases(final CanvasId canvasId,
                                      final Map<CanvasId, Set<CanvasId>> connectionsMap,
                                      final Set<CanvasId> connectedTileSet) {

        final boolean isNewConnection = connectedTileSet.add(canvasId);

        if (isNewConnection) {

            final Set<CanvasId> connectedCanvasIds = connectionsMap.remove(canvasId);

            if (connectedCanvasIds != null) {
                for (final CanvasId connectedCanvasId : connectedCanvasIds) {
                    addConnectedCanvases(connectedCanvasId, connectionsMap, connectedTileSet);
                }
            }

        }
    }


}

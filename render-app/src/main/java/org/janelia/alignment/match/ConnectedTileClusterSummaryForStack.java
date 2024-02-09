package org.janelia.alignment.match;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.janelia.alignment.spec.stack.StackId;

/**
 * Summary information about all connected tile clusters, all completely unconnected tiles, and
 * (optionally) all unconnected tile edges in a stack.
 *
 * @author Eric Trautman
 */
public class ConnectedTileClusterSummaryForStack
        implements Serializable {

    final StackId stackId;
    final List<ConnectedTileClusterSummary> tileClusterSummaryList;
    List<String> unconnectedTileIdList;
    List<String> unconnectedEdgeList;
    boolean hasTooManyConsecutiveUnconnectedEdges;

    public ConnectedTileClusterSummaryForStack(final StackId stackId) {
        this.stackId = stackId;
        this.tileClusterSummaryList = new ArrayList<>();
        this.unconnectedTileIdList = new ArrayList<>();
        this.unconnectedEdgeList = null;
        this.hasTooManyConsecutiveUnconnectedEdges = false;
    }

    public void addTileClusterSummary(final ConnectedTileClusterSummary clusterSummary) {
        tileClusterSummaryList.add(clusterSummary);
    }

    public void setUnconnectedTileIdList(final Set<String> allUnconnectedTileIds) {
        this.unconnectedTileIdList = allUnconnectedTileIds.stream().sorted().collect(Collectors.toList());
    }

    public void setUnconnectedEdgeData(final List<String> unconnectedEdgeList,
                                       final boolean hasTooManyConsecutiveUnconnectedEdges) {
        this.unconnectedEdgeList = new ArrayList<>(unconnectedEdgeList);
        this.hasTooManyConsecutiveUnconnectedEdges = hasTooManyConsecutiveUnconnectedEdges;
    }

    @Override
    public String toString() {
        final String clusterInfo = tileClusterSummaryList.size() + " cluster(s)";
        final String unconnectedTileInfo = unconnectedTileIdList.size() + " completely unconnected tile(s)";
        final String info;
        if (unconnectedEdgeList == null) {
            info = stackId + " has " + clusterInfo + " and " + unconnectedTileInfo;
        } else {
            final String edgeProblem =
                    hasTooManyConsecutiveUnconnectedEdges ?
                    " including at least one edge that is unconnected for too many consecutive layers" : "";
            info = stackId + " has " + clusterInfo + ", " + unconnectedTileInfo + ", and " +
                   unconnectedEdgeList.size() + " unconnected edge(s)" + edgeProblem;
        }
        return info;
    }

    public String toDetailsString() {
        final StringBuilder sb = new StringBuilder(toString()).append("\ncluster details:\n");
        for (final ConnectedTileClusterSummary tileSummary : tileClusterSummaryList) {
            sb.append("  ").append(tileSummary).append("\n");
        }
        sb.append("completely unconnected tiles:\n  ");
        sb.append(unconnectedTileIdList);

        if (unconnectedEdgeList != null) {
            sb.append("\nunconnected edges:\n");
            if (unconnectedEdgeList.isEmpty()) {
                sb.append("  none");
            } else {
                for (final String unconnectedEdge : unconnectedEdgeList) {
                    sb.append("  ").append(unconnectedEdge).append("\n");
                }
            }
        }
        return sb.toString();
    }

    public boolean hasMultipleClusters() {
        return tileClusterSummaryList.size() > 1;
    }

    public boolean hasUnconnectedTiles() {
        return ! unconnectedTileIdList.isEmpty();
    }

    public boolean hasTooManyConsecutiveUnconnectedEdges() {
        return hasTooManyConsecutiveUnconnectedEdges;
    }
}

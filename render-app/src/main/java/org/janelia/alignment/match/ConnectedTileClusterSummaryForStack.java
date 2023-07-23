package org.janelia.alignment.match;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.janelia.alignment.spec.stack.StackId;

/**
 * Summary information about all connected tile clusters and
 * all completely unconnected tiles in a stack.
 *
 * @author Eric Trautman
 */
public class ConnectedTileClusterSummaryForStack
        implements Serializable {

    final StackId stackId;
    final List<ConnectedTileClusterSummary> tileClusterSummaryList;
    List<String> unconnectedTileIdList;

    public ConnectedTileClusterSummaryForStack(final StackId stackId) {
        this.stackId = stackId;
        this.tileClusterSummaryList = new ArrayList<>();
        this.unconnectedTileIdList = new ArrayList<>();
    }

    public void addTileClusterSummary(final ConnectedTileClusterSummary clusterSummary) {
        tileClusterSummaryList.add(clusterSummary);
    }

    public void setUnconnectedTileIdList(final Set<String> allUnconnectedTileIds) {
        this.unconnectedTileIdList = allUnconnectedTileIds.stream().sorted().collect(Collectors.toList());
    }

    @Override
    public String toString() {
        return stackId + " has " + tileClusterSummaryList.size() + " cluster(s) and " +
                               unconnectedTileIdList.size() + " completely unconnected tile(s)";
    }

    public String toDetailsString() {
        final StringBuilder sb = new StringBuilder(toString()).append("\ncluster details:\n");
        for (final ConnectedTileClusterSummary tileSummary : tileClusterSummaryList) {
            sb.append("  ").append(tileSummary).append("\n");
        }
        sb.append("completely unconnected tiles:\n  ");
        sb.append(unconnectedTileIdList);
        return sb.toString();
    }
}

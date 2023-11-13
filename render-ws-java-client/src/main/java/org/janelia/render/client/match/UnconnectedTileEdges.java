package org.janelia.render.client.match;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.parameter.CellId;
import org.janelia.render.client.parameter.CellEdge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.Nonnull;

/**
 * Helper class for identifying long stretches of tile edges that are not connected in a stack
 * (adapted from original UnconnectedTileEdgeClient which now no longer exists).
 *
 * @author Eric Trautman
 */
public class UnconnectedTileEdges {

    final Integer maxUnconnectedLayers;
    final Map<CellEdge, List<ZRange>> edgeToUnconnectedZRangesMap;

    public UnconnectedTileEdges(final Integer maxUnconnectedLayers) {
        this.maxUnconnectedLayers = maxUnconnectedLayers;
        this.edgeToUnconnectedZRangesMap = new HashMap<>();
    }

    public void appendUnconnectedEdgesForZ(final Double z,
                                           final ResolvedTileSpecCollection resolvedTiles,
                                           final List<CanvasMatches> matchedPairs) {

        if ((maxUnconnectedLayers != null) && (maxUnconnectedLayers > 0)) {

            final Set<Integer> rowsForZ = new HashSet<>();
            final Set<Integer> columnsForZ = new HashSet<>();
            final Map<String, CellId> tileIdToCell = new HashMap<>();
            for (final TileSpec tileSpec : resolvedTiles.getTileSpecs()) {
                final LayoutData layout = tileSpec.getLayout();
                if (layout != null) {
                    rowsForZ.add(layout.getImageRow());
                    columnsForZ.add(layout.getImageCol());
                    tileIdToCell.put(tileSpec.getTileId(), new CellId(layout.getImageRow(),
                                                                      layout.getImageCol()));
                }
            }

            final List<Integer> sortedRows = rowsForZ.stream().sorted().collect(Collectors.toList());
            final List<Integer> sortedColumns = columnsForZ.stream().sorted().collect(Collectors.toList());
            final Set<CellId> cellsForZ = new HashSet<>(tileIdToCell.values());

            final List<CellEdge> expectedConnectedEdges = buildExpectedConnectedEdges(sortedRows,
                                                                                      sortedColumns,
                                                                                      cellsForZ);

            final Set<CellEdge> actualConnectedEdges = buildActualConnectedEdges(resolvedTiles, matchedPairs);

            updateUnconnectedEdgeMapForZ(z, expectedConnectedEdges, actualConnectedEdges);
        }
    }

    public List<String> buildSortedUnconnectedEdgeList() {
        final List<String> sortedUnconnectedEdges = new ArrayList<>();
        final List<CellEdge> sortedEdges = edgeToUnconnectedZRangesMap.keySet().stream().sorted().collect(Collectors.toList());
        for (final CellEdge edge : sortedEdges) {
            for (final ZRange zRange : edgeToUnconnectedZRangesMap.get(edge)) {
                sortedUnconnectedEdges.add("edge " + edge + " is not connected for " + zRange);
            }
        }
        return sortedUnconnectedEdges;
    }

    public boolean hasTooManyConsecutiveUnconnectedEdges() {
        return edgeToUnconnectedZRangesMap.values().stream()
                .flatMap(Collection::stream)
                .anyMatch(zRange -> zRange.size() > maxUnconnectedLayers);
    }

    private List<CellEdge> buildExpectedConnectedEdges(final List<Integer> sortedRows,
                                                       final List<Integer> sortedColumns,
                                                       final Set<CellId> cellsForZ) {
        final List<CellEdge> expectedConnectedEdges = new ArrayList<>();

        for (final Integer row : sortedRows) {
            for (int i = 1; i < sortedColumns.size(); i++) {
                final CellId previousCell = new CellId(row, sortedColumns.get(i - 1));
                final CellId cell = new CellId(row, sortedColumns.get(i));

                if (((cell.column - previousCell.column) == 1) &&
                    cellsForZ.contains(previousCell) &&
                    cellsForZ.contains(cell)) {

                    expectedConnectedEdges.add(new CellEdge(previousCell, cell));
                } // else gap in columns so skip
            }
        }

        for (final Integer column : sortedColumns) {
            for (int i = 1; i < sortedRows.size(); i++) {
                final CellId previousCell = new CellId(sortedRows.get(i - 1), column);
                final CellId cell = new CellId(sortedRows.get(i), column);

                if ((cell.row - previousCell.row) == 1 &&
                    cellsForZ.contains(previousCell) &&
                    cellsForZ.contains(cell)) {

                    expectedConnectedEdges.add(new CellEdge(previousCell, cell));
                } // else gap in rows so skip
            }
        }

        return expectedConnectedEdges;
    }

    @Nonnull
    private Set<CellEdge> buildActualConnectedEdges(final ResolvedTileSpecCollection resolvedTiles,
                                                    final List<CanvasMatches> matchedPairs) {
        final Set<CellEdge> connectedEdges = new HashSet<>();
        for (final CanvasMatches canvasMatches : matchedPairs) {
            // only consider matches within the same group
            if (canvasMatches.getpGroupId().equals(canvasMatches.getqGroupId())) {
                try {
                    connectedEdges.add(CellEdge.getEdgeForPair(canvasMatches.getpId(),
                                                               canvasMatches.getqId(),
                                                               resolvedTiles));
                } catch (final IllegalArgumentException e) {
                    LOG.warn("buildActualConnectedEdges: ignoring match pair " + canvasMatches.getpId() + " and " +
                             canvasMatches.getqId(), e);
                }
            }
        }
        return connectedEdges;
    }

    private void updateUnconnectedEdgeMapForZ(final Double z,
                                              final List<CellEdge> expectedConnectedEdges,
                                              final Set<CellEdge> actualConnectedEdges) {

        for (final CellEdge edge : expectedConnectedEdges) {
            if (! actualConnectedEdges.contains(edge)) {
                final List<ZRange> unconnectedZRangesForEdge =
                        edgeToUnconnectedZRangesMap.computeIfAbsent(edge, k -> new ArrayList<>());

                final int rangeCount = unconnectedZRangesForEdge.size();

                if (rangeCount == 0) {

                    unconnectedZRangesForEdge.add(new ZRange(z));

                } else {

                    final ZRange lastZRange = unconnectedZRangesForEdge.get(rangeCount - 1);
                    final int zDistance = lastZRange.distance(z);

                    if (zDistance < 0) {
                        LOG.debug("updateUnconnectedEdgeMapForZ: ignoring (duplicate) z {} since z {} has already been processed",
                                  z, lastZRange.lastZ);
                    } else if (zDistance <= 1) {
                        lastZRange.setLastZ(z);
                    } else if (lastZRange.size() > maxUnconnectedLayers) {
                        unconnectedZRangesForEdge.add(new ZRange(z));
                    } else {
                        lastZRange.reset(z);
                    }

                }
            }
        }
    }

    public static class ZRange {
        private double firstZ;
        private double lastZ;

        public ZRange(final double firstAndLastZ) {
            this.reset(firstAndLastZ);
        }

        public void reset(final double firstAndLastZ) {
            this.firstZ = firstAndLastZ;
            this.lastZ = firstAndLastZ;
        }

        public void setLastZ(final Double lastZ) {
            this.lastZ = lastZ;
        }

        public int distance(final Double z) {
            return z.intValue() - (int) lastZ;
        }

        public int size() {
            return (int) lastZ - (int) firstZ + 1;
        }

        @Override
        public String toString() {
            final int size = size();
            final String layerText = size == 1 ? " layer" : " layers";
            return size() + layerText + " from z " + firstZ + " to " + lastZ;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(UnconnectedTileEdges.class);
}

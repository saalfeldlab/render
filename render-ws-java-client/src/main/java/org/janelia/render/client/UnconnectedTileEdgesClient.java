package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.util.ArrayList;
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
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.CellId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for identifying long stretches of tile edges that are not connected in a stack.
 *
 * @author Eric Trautman
 */
public class UnconnectedTileEdgesClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Stack name",
                required = true)
        public String stack;

        @Parameter(
                names = "--matchOwner",
                description = "Match collection owner (default is to use stack owner)")
        public String matchOwner;

        @Parameter(
                names = "--matchCollection",
                description = "Match collection name",
                required = true)
        public String matchCollection;

        @Parameter(
                names = "--maxUnconnectedLayers",
                description = "Maximum number of unconnected layers to allow before flagging problem region",
                required = true)
        public Integer maxUnconnectedLayers;

        @Parameter(
                names = "--minZ",
                description = "Minimum Z value for layers to be processed")
        public Double minZ;

        @Parameter(
                names = "--maxZ",
                description = "Maximum Z value for layers to be processed")
        public Double maxZ;

        public String getMatchOwner() {
            return matchOwner == null ? renderWeb.owner : matchOwner;
        }
    }

    /**
     * @param  args  see {@link Parameters} for command line argument details.
     */
    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final UnconnectedTileEdgesClient client = new UnconnectedTileEdgesClient(parameters);
                client.findUnconnectedEdges();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    private UnconnectedTileEdgesClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    private void findUnconnectedEdges()
            throws Exception {

        final RenderDataClient renderDataClient = parameters.renderWeb.getDataClient();

        final RenderDataClient matchDataClient =
                new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                     parameters.getMatchOwner(),
                                     parameters.matchCollection);

        final Map<Double, Set<String>> zToSectionIdsMap =
                renderDataClient.getStackZToSectionIdsMap(parameters.stack,
                                                          parameters.minZ,
                                                          parameters.maxZ,
                                                          null);

        final List<Double> sortedZValues = zToSectionIdsMap.keySet().stream().sorted().collect(Collectors.toList());
        final Map<TileEdge, List<ZRange>> edgeToUnconnectedZRangesMap = new HashMap<>();

        for (final Double z : sortedZValues) {
            final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(parameters.stack, z);

            final List<CanvasMatches> matchedPairs = new ArrayList<>();
            for (final String sectionId : zToSectionIdsMap.get(z)) {
                matchedPairs.addAll(
                        matchDataClient.getMatchesWithinGroup(sectionId, true));
            }

            appendUnconnectedEdgesForZ(z, resolvedTiles, matchedPairs, edgeToUnconnectedZRangesMap);
        }

        final List<TileEdge> sortedEdges =
                edgeToUnconnectedZRangesMap.keySet().stream().sorted().collect(Collectors.toList());
        for (final TileEdge edge : sortedEdges) {
            for (final ZRange zRange : edgeToUnconnectedZRangesMap.get(edge)) {
                LOG.info("findUnconnectedEdges: edge {} is not connected for {}",
                         edge,
                         zRange);
            }
        }

        if (sortedEdges.size() == 0) {
            LOG.info("findUnconnectedEdges: no unconnected edges found!");
        }
    }

    protected static class ZRange {
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

    protected void appendUnconnectedEdgesForZ(final Double z,
                                              final ResolvedTileSpecCollection resolvedTiles,
                                              final List<CanvasMatches> matchedPairs,
                                              final Map<TileEdge, List<ZRange>> edgeToUnconnectedZRangesMap) {

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

        final List<TileEdge> expectedConnectedEdges = new ArrayList<>();

        for (final Integer row : sortedRows) {
            for (int i = 1; i < sortedColumns.size(); i++) {
                final CellId previousCell = new CellId(row, sortedColumns.get(i - 1));
                final CellId cell = new CellId(row, sortedColumns.get(i));

                if (((cell.column - previousCell.column) == 1) &&
                    cellsForZ.contains(previousCell)  &&
                    cellsForZ.contains(cell)) {

                    expectedConnectedEdges.add(new TileEdge(previousCell, cell));
                } // else gap in columns so skip
            }
        }

        for (final Integer column : sortedColumns) {
            for (int i = 1; i < sortedRows.size(); i++) {
                final CellId previousCell = new CellId(sortedRows.get(i - 1), column);
                final CellId cell = new CellId(sortedRows.get(i), column);

                if ((cell.row - previousCell.row) == 1 &&
                    cellsForZ.contains(previousCell)  &&
                    cellsForZ.contains(cell)) {

                    expectedConnectedEdges.add(new TileEdge(previousCell, cell));
                } // else gap in rows so skip
            }
        }

        final Set<TileEdge> connectedEdges = new HashSet<>();
        for (final CanvasMatches canvasMatches : matchedPairs) {
            try {
                connectedEdges.add(getEdgeForPair(canvasMatches.getpId(), canvasMatches.getqId(), resolvedTiles));
            } catch (final IllegalArgumentException e) {
                LOG.warn("ignoring match pair " + canvasMatches.getpId() + " and " + canvasMatches.getqId(), e);
            }
        }

        for (final TileEdge edge : expectedConnectedEdges) {

            if (! connectedEdges.contains(edge)) {

                final List<ZRange> unconnectedZRangesForEdge =
                        edgeToUnconnectedZRangesMap.computeIfAbsent(edge, k -> new ArrayList<>());

                final int rangeCount = unconnectedZRangesForEdge.size();

                if (rangeCount == 0) {

                    unconnectedZRangesForEdge.add(new ZRange(z));

                } else {

                    final ZRange lastZRange = unconnectedZRangesForEdge.get(rangeCount - 1);
                    final int zDistance = lastZRange.distance(z);

                    if (zDistance < 0) {
                        throw new IllegalArgumentException("out of order z " + z + " given last z range " + lastZRange);
                    } else if (zDistance <= 1) {
                        lastZRange.setLastZ(z);
                    } else if (lastZRange.size() > parameters.maxUnconnectedLayers){
                        unconnectedZRangesForEdge.add(new ZRange(z));
                    } else {
                        lastZRange.reset(z);
                    }

                }
            }

        }

    }

    public static class TileEdge implements Comparable<TileEdge> {
        private final CellId fromCell;
        private final CellId toCell;

        public TileEdge(final CellId cellA,
                        final CellId cellB) {
            // need to normalize edge ordering
            if (cellA.row == cellB.row) {

                if (cellA.column < cellB.column) {
                    this.fromCell = cellA;
                    this.toCell = cellB;
                } else if (cellA.column > cellB.column) {
                    this.fromCell = cellB;
                    this.toCell = cellA;
                } else {
                    throw new IllegalArgumentException("tile edge cannot have both same row and same column");
                }

                final int delta = this.toCell.column - this.fromCell.column;
                if (delta != 1) {
                    throw new IllegalArgumentException("tile edge cannot have column delta " +   delta);
                }

            } else if (cellA.column == cellB.column) {

                if (cellA.row < cellB.row) {
                    this.fromCell = cellA;
                    this.toCell = cellB;
                } else {
                    this.fromCell = cellB;
                    this.toCell = cellA;
                }

                final int delta = this.toCell.row - this.fromCell.row;
                if (delta != 1) {
                    throw new IllegalArgumentException("tile edge cannot have row delta " +   delta);
                }

            } else {
                throw new IllegalArgumentException("tile edge must have either same row or same column");
            }
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            final TileEdge tileEdge = (TileEdge) o;

            if (!fromCell.equals(tileEdge.fromCell)) {
                return false;
            }
            return toCell.equals(tileEdge.toCell);
        }

        @Override
        public int hashCode() {
            int result = fromCell.hashCode();
            result = 31 * result + toCell.hashCode();
            return result;
        }

        @Override
        public int compareTo(final TileEdge that) {
            int result = this.fromCell.compareTo(that.fromCell);
            if (result == 0) {
                result = this.toCell.compareTo(that.toCell);
            }
            return result;
        }

        @Override
        public String toString() {
            return "{\"from\": " + fromCell +
                   ", \"to\": " + toCell +
                   '}';
        }
    }

    public static CellId fromTileSpec(final TileSpec tileSpec) {
        CellId cell = null;
        if (tileSpec != null) {
            final LayoutData layout = tileSpec.getLayout();
            if (layout != null) {
                cell = new CellId(layout.getImageRow(), layout.getImageCol());
            }
        }
        return cell;
    }

    private TileEdge getEdgeForPair(final String pId,
                                    final String qId,
                                    final ResolvedTileSpecCollection resolvedTiles) {
        TileEdge edge = null;
        final CellId pCell = fromTileSpec(resolvedTiles.getTileSpec(pId));
        final CellId qCell = fromTileSpec(resolvedTiles.getTileSpec(qId));
        if ((pCell != null) && (qCell != null)) {
            edge = new TileEdge(pCell, qCell);
        }
        return edge;
    }

    private static final Logger LOG = LoggerFactory.getLogger(UnconnectedTileEdgesClient.class);
}

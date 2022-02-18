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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for identifying tile columns that are not connected in a stack.
 *
 * @author Eric Trautman
 */
public class UnconnectedColumnClient {

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

                final UnconnectedColumnClient client = new UnconnectedColumnClient(parameters);
                client.findUnconnectedColumns();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    private UnconnectedColumnClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    private void findUnconnectedColumns()
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
        final Map<Integer, List<ZRange>> columnToUnconnectedZRangesMap = new HashMap<>();

        for (final Double z : sortedZValues) {
            final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(parameters.stack, z);

            final List<CanvasMatches> matchedPairs = new ArrayList<>();
            for (final String sectionId : zToSectionIdsMap.get(z)) {
                matchedPairs.addAll(
                        matchDataClient.getMatchesWithinGroup(sectionId, true));
            }

            appendUnconnectedColumnsForZ(z, resolvedTiles, matchedPairs, columnToUnconnectedZRangesMap);
        }

        final List<Integer> sortedColumns =
                columnToUnconnectedZRangesMap.keySet().stream().sorted().collect(Collectors.toList());
        for (final Integer column : sortedColumns) {
            for (final ZRange zRange : columnToUnconnectedZRangesMap.get(column)) {
                LOG.info("findUnconnectedColumns: tiles in columns {} and {} are not connected for {}",
                         column,
                         column + 1,
                         zRange);
            }
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

    protected void appendUnconnectedColumnsForZ(final Double z,
                                                final ResolvedTileSpecCollection resolvedTiles,
                                                final List<CanvasMatches> matchedPairs,
                                                final Map<Integer, List<ZRange>> columnToUnconnectedZRangesMap) {

        final Set<Integer> columnsForZ = new HashSet<>();
        for (final TileSpec tileSpec : resolvedTiles.getTileSpecs()) {
            final LayoutData layout = tileSpec.getLayout();
            if (layout != null) {
                columnsForZ.add(layout.getImageCol());
            }
        }

        final List<Integer> expectedConnectedColumns = new ArrayList<>();
        Integer previousColumn = null;
        for (final Integer column : columnsForZ.stream().sorted().collect(Collectors.toList())) {
            if (previousColumn != null) {
                if ((column - previousColumn) == 1) {
                    expectedConnectedColumns.add(previousColumn);
                } // else gap in columns so skip
            }
            previousColumn = column;
        }

        final Set<Integer> connectedColumns = new HashSet<>();
        for (final CanvasMatches canvasMatches : matchedPairs) {

            final Integer pColumn = getColumnForTileId(canvasMatches.getpId(), resolvedTiles);
            final Integer qColumn = getColumnForTileId(canvasMatches.getqId(), resolvedTiles);

            if ((pColumn != null) && (qColumn != null)) {
                final int columnDelta = qColumn - pColumn;
                if (columnDelta == 1) {
                    connectedColumns.add(pColumn);
                } else if (columnDelta == -1) {
                    connectedColumns.add(qColumn);
                }
            }
        }

        for (final Integer column : expectedConnectedColumns) {

            if (! connectedColumns.contains(column)) {

                final List<ZRange> unconnectedZRangesForColumn =
                        columnToUnconnectedZRangesMap.computeIfAbsent(column, k -> new ArrayList<>());

                final int rangeCount = unconnectedZRangesForColumn.size();

                if (rangeCount == 0) {

                    unconnectedZRangesForColumn.add(new ZRange(z));

                } else {

                    final ZRange lastZRange = unconnectedZRangesForColumn.get(rangeCount - 1);
                    final int zDistance = lastZRange.distance(z);

                    if (zDistance < 0) {
                        throw new IllegalArgumentException("out of order z " + z + " given last z range " + lastZRange);
                    } else if (zDistance <= 1) {
                        lastZRange.setLastZ(z);
                    } else if (lastZRange.size() > parameters.maxUnconnectedLayers){
                        unconnectedZRangesForColumn.add(new ZRange(z));
                    } else {
                        lastZRange.reset(z);
                    }

                }
            }

        }

    }

    private Integer getColumnForTileId(final String tileId,
                                       final ResolvedTileSpecCollection resolvedTiles) {
        Integer column = null;
        final TileSpec tileSpec = resolvedTiles.getTileSpec(tileId);
        if (tileSpec != null) {
            final LayoutData layout = tileSpec.getLayout();
            if (layout != null) {
                column = layout.getImageCol();
            }
        }
        return column;
    }

    private static final Logger LOG = LoggerFactory.getLogger(UnconnectedColumnClient.class);
}

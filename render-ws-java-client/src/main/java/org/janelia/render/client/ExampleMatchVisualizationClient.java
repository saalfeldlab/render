package org.janelia.render.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileBoundsRTree;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.LayerBoundsParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.ZRangeParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

/**
 * Java client example for Stephan Preibisch to start developing visualization tools for match data.
 *
 * @author Eric Trautman
 */
public class ExampleMatchVisualizationClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Stack name",
                required = true)
        public String stack;

        @ParametersDelegate
        public ZRangeParameters layerRange = new ZRangeParameters();

        @Parameter(
                names = "--sameLayerNeighborFactor",
                description = "This value is multiplied by max(width, height) of each tile to " +
                              "determine radius for locating same layer neighbor tiles",
                required = true
        )
        public Double sameLayerNeighborFactor;

        @Parameter(
                names = "--crossLayerNeighborFactor",
                description = "This value is multiplied by max(width, height) of each tile to " +
                              "determine radius for locating cross layer neighbor tiles",
                required = true
        )
        public Double crossLayerNeighborFactor;

        @ParametersDelegate
        public LayerBoundsParameters bounds = new LayerBoundsParameters();

        @Parameter(
                names = "--matchOwner",
                description = "Match owner (default is same as owner)")
        public String matchOwner;

        @Parameter(
                names = "--matchCollection",
                description = "Match collection",
                required = true)
        public String matchCollection;

        public Parameters() {
        }

        String getMatchOwner() {
            return matchOwner == null ? renderWeb.owner : matchOwner;
        }

    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);
                parameters.bounds.validate();

                LOG.info("runClient: entry, parameters={}", parameters);

                final ExampleMatchVisualizationClient client = new ExampleMatchVisualizationClient(parameters);

                client.printConnections();
            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;
    private final boolean filterTilesWithBox;
    private final RenderDataClient renderDataClient;
    private final RenderDataClient matchDataClient;

    private final Map<Double, List<String>> zToSectionIdMap;

    /**
     * Basic constructor.
     *
     * @param  parameters  parameters for this client.
     *
     * @throws IllegalArgumentException
     *   if any of the parameters are invalid.
     *
     * @throws IOException
     *   if there are any problems retrieving data from the render web services.
     */
    ExampleMatchVisualizationClient(final Parameters parameters)
            throws IllegalArgumentException, IOException {

        this.parameters = parameters;
        this.filterTilesWithBox = (parameters.bounds.minX != null);

        // utility to submit HTTP requests to the render web service for render (tile and transform) data
        this.renderDataClient = parameters.renderWeb.getDataClient();

        // utility to submit HTTP requests to the render web service for match data
        this.matchDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                    parameters.getMatchOwner(),
                                                    parameters.matchCollection);

        // A tile's sectionId is a logically immutable string value that typically corresponds to
        // the acquisition system's estimate of the tile's z position in the stack.
        // A tile's z value is the aligned double z position of the tile in the stack.
        // In most cases, z is simply the parsed double form of a tile's string sectionId.
        // However, tiles in reordered layers will have a z that differs from the tile's sectionId.
        // Reacquired tiles also have differing sectionId values (typically <z>.1, <z>.2, ...).

        // Point matches are stored using the tile's immutable sectionId (as groupId) and tileId (as id)
        // so that we can reorder tile layers without needing to change/update previously stored match data.

        // retrieve the list of sections for the stack (optionally filtered by a range)
        final List<SectionData> stackSectionDataList =
                renderDataClient.getStackSectionData(parameters.stack,
                                                     parameters.layerRange.minZ,
                                                     parameters.layerRange.maxZ);

        // map sections to z values (usually this is one-to-one, but it may not be)
        this.zToSectionIdMap = new HashMap<>(stackSectionDataList.size());
        for (final SectionData sectionData : stackSectionDataList) {
            final List<String> sectionIdList = zToSectionIdMap.computeIfAbsent(sectionData.getZ(),
                                                                               z -> new ArrayList<>());
            sectionIdList.add(sectionData.getSectionId());
        }

    }

    /**
     * Retrieves tile bounds and point match correspondence data, printing out tile ids and connection symbols
     * ('::' for existing connections and '??' for missing connections) to standard out.
     * This is sufficient for the single row FIB-SEM stacks with the same number of tiles in each layer.
     * It won't work for many other cases.
     *
     * Since the primary goal of the client is to demonstrate how to pull tile and match data from render,
     * it has been left in its current less-than-ideal state.
     *
     * @throws IllegalArgumentException
     *   if any of the parameters are invalid.
     *
     * @throws IOException
     *   if there are any problems retrieving data from the render web services.
     */
    void printConnections()
            throws IllegalArgumentException, IOException {

        final List<Double> zValues = zToSectionIdMap.keySet().stream().sorted().collect(Collectors.toList());

        if (zValues.size() == 0) {
            throw new IllegalArgumentException(
                    "stack " + parameters.stack + " does not contain any layers with the specified z values");
        }

        // buffer connections output so that it can be printed once at the end instead of interleaving it with log info
        final StringBuilder output = new StringBuilder(4096);
        output.append("\n\n");

        TileBoundsRTree currentLayerBoundsTree = buildRTreeForLayer(zValues.get(0));
        Set<OrderedCanvasIdPair> currentLayerMatchedPairs = getMatchedPairsForLayer(zValues.get(0));

        for (int zIndex = 1; zIndex <= zValues.size(); zIndex++) {

            final int startingOutputLength = output.length();

            // use the R Tree to find pairs of adjacent tiles in the same layer that "should" have matches
            final List<OrderedCanvasIdPair> sameLayerNeighborPairs =
                    currentLayerBoundsTree.getCircleNeighbors(currentLayerBoundsTree.getTileBoundsList(),
                                                               new ArrayList<>(),
                                                               parameters.sameLayerNeighborFactor,
                                                               null,
                                                               true,
                                                               false,
                                                               false)
                            .stream()
                            .map(pair -> {
                                // Minor Hack: Remove same layer relative position data inserted by
                                //             getCircleNeighbors for tile pair client.
                                //             This allows later comparisons with retrieved match pairs
                                //             (which do not have position data) to work as intended.
                                return new OrderedCanvasIdPair(pair.getP().withoutRelativePosition(),
                                                               pair.getQ().withoutRelativePosition(),
                                                               null);
                            })
                            .sorted()
                            .collect(Collectors.toList());


            for (final OrderedCanvasIdPair pair : sameLayerNeighborPairs) {
                if (! currentLayerMatchedPairs.contains(pair)) {
                    appendMissingPair(pair, true, output);
                }
            }

            // for all but the last layer ...
            if (zIndex < zValues.size()) {
                
                final double z = zValues.get(zIndex);

                final TileBoundsRTree nextLayerBoundsTree = buildRTreeForLayer(z);

                // use the R Tree to find pairs of adjacent tiles in the next layer that "should" have matches
                final List<OrderedCanvasIdPair> crossLayerNeighborPairs =
                        currentLayerBoundsTree.getCircleNeighbors(currentLayerBoundsTree.getTileBoundsList(),
                                                                   Collections.singletonList(nextLayerBoundsTree),
                                                                   parameters.crossLayerNeighborFactor,
                                                                   null,
                                                                   true,
                                                                   true,
                                                                   false)
                                .stream().sorted().collect(Collectors.toList());

                for (final OrderedCanvasIdPair pair : crossLayerNeighborPairs) {
                    if (! currentLayerMatchedPairs.contains(pair)) {
                        appendMissingPair(pair, false, output);
                    }
                }

                if (output.length() > startingOutputLength) {
                    output.append("\n");
                }

                currentLayerBoundsTree = nextLayerBoundsTree;
                currentLayerMatchedPairs = getMatchedPairsForLayer(z);
            }
        }

        // print
        System.out.println(output);
    }

    // Fetches tile bounds data and loads it into an RTree to facilitate location searches.
    private  TileBoundsRTree buildRTreeForLayer(final double z)
            throws IOException {

        TileBoundsRTree tree;
        List<TileBounds> tileBoundsList;
        final int totalTileCount;

        // We only need tile bounds (as opposed to full tile specs with transformation data) to determine neighbors.
        // Retrieving bounds is much faster.
        // If you need full tile specs, see renderDataClient.getResolvedTiles(...).

        tileBoundsList = renderDataClient.getTileBounds(parameters.stack, z);
        totalTileCount = tileBoundsList.size();

        tree = new TileBoundsRTree(z, tileBoundsList);

        if (filterTilesWithBox) {

            final int unfilteredCount = tileBoundsList.size();

            tileBoundsList = tree.findTilesInBox(parameters.bounds.minX, parameters.bounds.minY,
                                                 parameters.bounds.maxX, parameters.bounds.maxY);

            if (unfilteredCount > tileBoundsList.size()) {

                LOG.info("buildRTree: removed {} tiles outside of bounding box",
                         (unfilteredCount - tileBoundsList.size()));

                tree = new TileBoundsRTree(z, tileBoundsList);
            }
        }

        LOG.info("buildRTree: added bounds for {} out of {} tiles for z {}",
                 tileBoundsList.size(), totalTileCount, z);

        return tree;
    }

    // Fetches match data.
    private Set<OrderedCanvasIdPair> getMatchedPairsForLayer(final double z)
            throws IOException {

        final Set<OrderedCanvasIdPair> pairs = new HashSet<>();

        final List<String> groupIds = zToSectionIdMap.get(z);

        // We only need connection information (not specific match points),
        // so excluding match details is sufficient and will make requests much faster.
        final boolean excludeMatchDetails = true;

        if (groupIds != null) {

            // Typically groupId is the same as z, but it can be different (see long explanation above).
            for (final String pGroupId : groupIds) {
                for (final CanvasMatches canvasMatches : matchDataClient.getMatchesWithPGroupId(pGroupId,
                                                                                                excludeMatchDetails)) {
                    final OrderedCanvasIdPair pair =  new OrderedCanvasIdPair(
                            new CanvasId(canvasMatches.getpGroupId(), canvasMatches.getpId()),
                            new CanvasId(canvasMatches.getqGroupId(), canvasMatches.getqId()),
                            null);

                    pairs.add(pair);
                }
            }
        }
        return pairs;
    }

    private void appendMissingPair(final OrderedCanvasIdPair pair,
                                   final boolean isSameLayer,
                                   final StringBuilder output) {
        final String connection = isSameLayer ? " >> " : " vv ";
        output.append(pair.getP().getId()).append(connection).append(pair.getQ().getId()).append(" || ");
    }

    private static final Logger LOG = LoggerFactory.getLogger(ExampleMatchVisualizationClient.class);

}